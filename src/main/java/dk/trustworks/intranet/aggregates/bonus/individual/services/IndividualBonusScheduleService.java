package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.*;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.intranet.utils.DateUtils.fiscalYearStart;

/**
 * LAYER 1 · PROJECTION — read-time only, writes NOTHING.
 * <p>
 * Expands each active rule's spec into the future payouts it will produce, truncated at
 * {@code min(effective_to, TERMINATION month, horizon)}, overlaying any already-materialised
 * salary_lump_sum (→ COMMITTED with the actual amount). Because nothing future is persisted, early
 * termination needs no cleanup — the projection simply stops.
 * <p>
 * Business math is delegated to {@link IndividualBonusEvaluator}; fact lookups to
 * {@link IndividualBonusBasisResolver}.
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusScheduleService {

    private static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);

    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusEvaluator evaluator;
    @Inject IndividualBonusBasisResolver basisResolver;

    /** All projected + committed payouts for a user up to {@code horizonEnd}, sorted by month. */
    public List<ProjectedPayout> project(String userUuid, LocalDate horizonEnd) {
        List<ProjectedPayout> out = new ArrayList<>();
        for (IndividualBonusRule rule : IndividualBonusRule.findActiveByUser(userUuid)) {
            out.addAll(project(rule, horizonEnd));
        }
        out.sort(Comparator.comparing(ProjectedPayout::month));
        return out;
    }

    /** DISPLAY projection: advances reconciled against committed-OR-projected amounts (smooth forecast). */
    public List<ProjectedPayout> project(IndividualBonusRule rule, LocalDate horizonEnd) {
        return project(rule, horizonEnd, false);
    }

    /**
     * @param settlementFromCommittedAdvancesOnly at MATERIALISATION a TRUEUP / FINAL_SETTLEMENT must net
     *   only advances ACTUALLY PAID (committed lump sums) — a projected-but-unpaid advance counts as 0 —
     *   so the row written to payroll is {@code earned − Σ paid advances}. The DISPLAY projection (false)
     *   nets committed-or-projected so the on-screen forecast stays continuous before the advances post.
     */
    public List<ProjectedPayout> project(IndividualBonusRule rule, LocalDate horizonEnd,
                                         boolean settlementFromCommittedAdvancesOnly) {
        return projectWithInputs(rule, horizonEnd, settlementFromCommittedAdvancesOnly).stream()
                .map(PayoutWithInputs::payout)
                .toList();
    }

    /**
     * A projected payout paired with the basis inputs that produced it — the reproducibility snapshot the
     * materialisation writer freezes into {@code individual_bonus_payout}. Package-visible.
     */
    public record PayoutWithInputs(ProjectedPayout payout, BigDecimal basisAmount, int monthsEmployed) {
    }

    /** Projection carrying per-payout basis inputs. Same walk as {@link #project}; single source of truth. */
    List<PayoutWithInputs> projectWithInputs(IndividualBonusRule rule, LocalDate horizonEnd,
                                             boolean settlementFromCommittedAdvancesOnly) {
        Spec spec = specMapper.parse(rule.getSpec());

        // 1. Effective window, cut by early termination.
        // NOTE: termination is resolved GLOBALLY here. Multi-company scoping (spec §7.4 — a leaver
        // in company A who is active in B keeps B's schedule) needs the rule's companyUuid, which the
        // table does not yet carry. TODO (Phase 3, D): store companyUuid and scope this.
        LocalDate termMonth = effectiveTerminationMonth(rule.getUserUuid(), horizonEnd);
        LocalDate ruleEnd = rule.getEffectiveTo() != null ? rule.getEffectiveTo() : FAR_FUTURE;
        LocalDate windowEnd = min(min(ruleEnd, termMonth == null ? FAR_FUTURE : termMonth), horizonEnd);

        List<PayoutWithInputs> payouts = new ArrayList<>();

        // 2. Walk each fiscal year overlapping [effectiveFrom, windowEnd]. The window bounds the EARNING
        //    period; a trailing settlement (paid AFTER FY close) may legitimately fall past windowEnd.
        int fromFy = fiscalYearStart(rule.getEffectiveFrom()).getYear();
        int toFy = fiscalYearStart(windowEnd).getYear();
        for (int fyYear = fromFy; fyYear <= toFy; fyYear++) {
            LocalDate fyStart = LocalDate.of(fyYear, 7, 1);
            LocalDate fyEnd = LocalDate.of(fyYear + 1, 6, 30);
            LocalDate empFrom = max(rule.getEffectiveFrom(), fyStart);
            LocalDate empTo = min(windowEnd, fyEnd);
            if (empFrom.isAfter(empTo)) continue; // no earning overlap this FY → no settlement

            int monthsInFy = basisResolver.monthsActive(rule.getUserUuid(), empFrom, empTo);
            boolean estimated = empTo.isAfter(LocalDate.now()); // window not fully settled yet
            BigDecimal basisAmount = resolveBasisAmount(spec, rule.getUserUuid(), empFrom, empTo);
            BigDecimal earned = earnedFrom(spec, basisAmount, monthsInFy);

            // A yearly/true-up payout for a terminated FY settles at the termination month, not FY-end.
            boolean fyCutByTerm = termMonth != null
                    && !termMonth.isBefore(fyStart) && !termMonth.isAfter(fyEnd);
            LocalDate settleMonth = fyCutByTerm ? termMonth : yearlyPayMonth(fyEnd, spec);

            switch (spec.schedule().cadence()) {
                case YEARLY -> payouts.add(new PayoutWithInputs(new ProjectedPayout(
                        settleMonth, earned, PayoutKind.YEARLY, PayoutStatus.PROJECTED,
                        refYearly(rule, fyYear), estimated, fyCutByTerm), basisAmount, monthsInFy));

                case MONTHLY -> {
                    for (YearMonth m : employedMonths(empFrom, empTo)) {
                        payouts.add(new PayoutWithInputs(new ProjectedPayout(
                                m.atDay(1), monthlyAmount(spec, earned, monthsInFy),
                                PayoutKind.MONTHLY, PayoutStatus.PROJECTED,
                                refAdvance(rule, m), estimated, false), basisAmount, monthsInFy));
                    }
                }

                case MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP -> {
                    BigDecimal advancesPaid = BigDecimal.ZERO;
                    for (YearMonth m : employedMonths(empFrom, empTo)) {
                        BigDecimal projected = advanceAmount(spec, earned, monthsInFy);
                        // Reconcile the true-up against advances actually paid: a committed lump sum is
                        // authoritative; a still-projected advance counts as PAID for DISPLAY but as ZERO
                        // at materialisation (settlementFromCommittedAdvancesOnly) so payroll nets only
                        // real advances.
                        BigDecimal committed = committedAmount(refAdvance(rule, m));
                        advancesPaid = advancesPaid.add(
                                advanceContribution(committed, projected, settlementFromCommittedAdvancesOnly));
                        payouts.add(new PayoutWithInputs(new ProjectedPayout(
                                m.atDay(1), projected, PayoutKind.ADVANCE, PayoutStatus.PROJECTED,
                                refAdvance(rule, m), estimated, false), basisAmount, monthsInFy));
                    }
                    // FY-close true-up (or FINAL_SETTLEMENT on termination) = earned(-to-termination)
                    // − Σ advances-paid.
                    BigDecimal trueUp = reconcileTrueUp(spec, earned, advancesPaid);
                    PayoutKind kind = fyCutByTerm ? PayoutKind.FINAL_SETTLEMENT : PayoutKind.TRUEUP;
                    payouts.add(new PayoutWithInputs(new ProjectedPayout(
                            settleMonth, trueUp, kind, PayoutStatus.PROJECTED,
                            refTrueUp(rule, fyYear), estimated, fyCutByTerm), basisAmount, monthsInFy));
                }
            }
        }

        // 3. Bound each payout (advances by the earning window; a trailing settlement only by the horizon
        //    so a fully-earned FY does not lose its post-window pay-month), then overlay committed lump sums.
        return payouts.stream()
                .filter(pi -> survivesWindow(pi.payout().kind(), pi.payout().month(), windowEnd, horizonEnd))
                .map(pi -> new PayoutWithInputs(overlayCommitted(pi.payout()), pi.basisAmount(), pi.monthsEmployed()))
                .sorted(Comparator.comparing(pi -> pi.payout().month()))
                .toList();
    }

    // --- amount helpers ---

    private BigDecimal resolveBasisAmount(Spec spec, String userUuid, LocalDate from, LocalDate to) {
        if (spec.basis() == Basis.FIXED_AMOUNT) return BigDecimal.ZERO; // amount comes from the schedule
        return basisResolver.resolveBasisAmount(spec.basis(), userUuid, from, to);
    }

    private BigDecimal earnedFrom(Spec spec, BigDecimal basisAmount, int months) {
        if (spec.basis() == Basis.FIXED_AMOUNT) return BigDecimal.ZERO; // amount comes from the schedule
        BigDecimal earned = evaluator.computeEarned(spec.tierTable(), basisAmount, spec.proRating(), months);
        if (spec.cap() != null && earned.compareTo(spec.cap()) > 0) earned = spec.cap();
        return earned;
    }

    // --- pure, package-visible decision helpers (unit-tested without booting Quarkus) ---

    /** The mechanical recurring advance/monthly kinds — written by the monthly job and gated on ACTIVE. */
    static boolean isMonthlyAdvanceKind(PayoutKind kind) {
        return kind == PayoutKind.ADVANCE || kind == PayoutKind.MONTHLY;
    }

    /** The FY-settlement kinds — paid AFTER the earning window closes; admin-confirmed at materialisation. */
    static boolean isSettlementKind(PayoutKind kind) {
        return kind == PayoutKind.YEARLY || kind == PayoutKind.TRUEUP || kind == PayoutKind.FINAL_SETTLEMENT;
    }

    /**
     * Whether a payout survives the projection cut. Recurring advances are bounded by the earning window
     * (termination / effective-to). A settlement is bounded only by the horizon, so a fully-earned FY keeps
     * its trailing pay-month even when it falls after {@code effectiveTo} (spec §12.3 / bounded-yearly bug).
     */
    static boolean survivesWindow(PayoutKind kind, LocalDate month, LocalDate windowEnd, LocalDate horizonEnd) {
        LocalDate bound = isSettlementKind(kind) ? horizonEnd : windowEnd;
        return !month.isAfter(bound);
    }

    /**
     * A single month's advance contribution to the true-up reconciliation: a committed (paid) amount is
     * always authoritative; an un-materialised advance counts as ZERO when {@code committedOnly} (the
     * materialisation path) and as its projection otherwise (the display path).
     */
    static BigDecimal advanceContribution(BigDecimal committed, BigDecimal projected, boolean committedOnly) {
        if (committed != null) return committed;
        if (committedOnly) return BigDecimal.ZERO;
        return projected != null ? projected : BigDecimal.ZERO;
    }

    private BigDecimal monthlyAmount(Spec spec, BigDecimal earned, int monthsInFy) {
        Advance advance = spec.schedule().advance();
        if (spec.basis() == Basis.FIXED_AMOUNT && advance != null && advance.fixedAmountPerMonth() != null) {
            return advance.fixedAmountPerMonth();
        }
        if (monthsInFy <= 0) return BigDecimal.ZERO;
        return earned.divide(BigDecimal.valueOf(monthsInFy), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal advanceAmount(Spec spec, BigDecimal earned, int monthsInFy) {
        Advance advance = spec.schedule().advance();
        if (advance == null) return BigDecimal.ZERO;
        if (advance.type() == AdvanceType.FIXED && advance.fixedAmountPerMonth() != null) {
            return advance.fixedAmountPerMonth();
        }
        if (advance.type() == AdvanceType.PERCENT_OF_PROJECTED && advance.percentOfProjected() != null
                && monthsInFy > 0) {
            return earned.multiply(advance.percentOfProjected())
                    .divide(BigDecimal.valueOf(monthsInFy), 0, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    /**
     * The FY-close true-up (or termination FINAL_SETTLEMENT) amount: {@code earned − Σ advances-paid},
     * after {@link #applyNegativeHandling}. Package-visible so the reconciliation math can be unit-tested
     * without booting Quarkus — the injected collaborators are never touched here.
     */
    BigDecimal reconcileTrueUp(Spec spec, BigDecimal earned, BigDecimal advancesPaid) {
        return applyNegativeHandling(spec, earned.subtract(advancesPaid));
    }

    /** WRITE_OFF (default, D1) clamps a negative true-up to 0; CLAWBACK keeps the negative. */
    private BigDecimal applyNegativeHandling(Spec spec, BigDecimal trueUp) {
        if (trueUp.signum() >= 0) return trueUp;
        TrueUp t = spec.schedule().trueUp();
        NegativeHandling handling = (t != null && t.negativeHandling() != null)
                ? t.negativeHandling() : NegativeHandling.WRITE_OFF;
        return handling == NegativeHandling.CLAWBACK ? trueUp : BigDecimal.ZERO;
    }

    private static LocalDate yearlyPayMonth(LocalDate fyEnd, Spec spec) {
        int offset = 1; // default: FY ends Jun 30 → pay in July (FY+1)
        if (spec.schedule().yearly() != null && spec.schedule().yearly().payMonthOffsetFromFyEnd() > 0) {
            offset = spec.schedule().yearly().payMonthOffsetFromFyEnd();
        }
        return fyEnd.plusMonths(offset).withDayOfMonth(1);
    }

    private static List<YearMonth> employedMonths(LocalDate from, LocalDate to) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!cursor.isAfter(last)) {
            months.add(cursor);
            cursor = cursor.plusMonths(1);
        }
        return months;
    }

    // --- overlay & termination ---

    private ProjectedPayout overlayCommitted(ProjectedPayout p) {
        BigDecimal actual = committedAmount(p.sourceReference());
        if (actual == null) return p;
        return new ProjectedPayout(p.month(), actual, p.kind(), PayoutStatus.COMMITTED,
                p.sourceReference(), false, p.truncatedByTermination());
    }

    /** The committed (actually-paid) gross amount for a sourceReference, or null if not yet materialised. */
    private static BigDecimal committedAmount(String sourceRef) {
        return SalaryLumpSum.<SalaryLumpSum>find("sourceReference", sourceRef)
                .firstResultOptional()
                .map(ls -> BigDecimal.valueOf(ls.getLumpSum()))
                .orElse(null);
    }

    /**
     * The termination month (day-1) if the user is terminated as of {@code horizonEnd}, else null.
     * Delegates the tie-break to {@link #effectiveStatusAsOf}.
     */
    private LocalDate effectiveTerminationMonth(String userUuid, LocalDate horizonEnd) {
        return effectiveStatusAsOf(UserStatus.findByUseruuid(userUuid), horizonEnd)
                .filter(s -> s.getStatus() == StatusType.TERMINATED)
                .map(s -> s.getStatusdate().withDayOfMonth(1))
                .orElse(null);
    }

    /**
     * The effective {@link UserStatus} as of {@code asOf}: latest statusdate wins and — for a PAYOUT path —
     * a TERMINATED row wins a SAME-DATE tie (money-safe: a leaver is not paid).
     * <p>
     * NOTE: this is intentionally STRICTER than {@code User.getUserStatus}, which keeps ACTIVE on a
     * same-date tie ({@code ? 0 : 1}). The spec (§2.4/§7) mandates TERMINATED-wins for termination
     * handling, so this bonus path deliberately diverges to avoid paying a same-date leaver. Package-visible
     * and static so both {@link IndividualBonusPayoutService} and this class share it and it is unit-testable.
     */
    static Optional<UserStatus> effectiveStatusAsOf(List<UserStatus> statuses, LocalDate asOf) {
        return statuses.stream()
                .filter(s -> s.getStatusdate() != null && !s.getStatusdate().isAfter(asOf))
                .max(Comparator.comparing(UserStatus::getStatusdate)
                        .thenComparing(s -> s.getStatus() == StatusType.TERMINATED ? 1 : 0));
    }

    // --- sourceReference scheme (stable, idempotent; equals the lump sum's ref once materialised) ---

    static String refYearly(IndividualBonusRule rule, int fyYear) {
        return "individual:" + rule.getUuid() + ":yearly:" + fyLabel(fyYear);
    }

    static String refAdvance(IndividualBonusRule rule, YearMonth month) {
        return "individual:" + rule.getUuid() + ":advance:" + String.format("%04d%02d",
                month.getYear(), month.getMonthValue());
    }

    static String refTrueUp(IndividualBonusRule rule, int fyYear) {
        return "individual:" + rule.getUuid() + ":trueup:" + fyLabel(fyYear);
    }

    private static String fyLabel(int fyYear) {
        return fyYear + "_" + (fyYear + 1);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
