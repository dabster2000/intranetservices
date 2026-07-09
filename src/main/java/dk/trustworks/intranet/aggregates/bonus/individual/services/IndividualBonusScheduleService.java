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

    public List<ProjectedPayout> project(IndividualBonusRule rule, LocalDate horizonEnd) {
        Spec spec = specMapper.parse(rule.getSpec());

        // 1. Effective window, cut by early termination.
        // NOTE: termination is resolved GLOBALLY here. Multi-company scoping (spec §7.4 — a leaver
        // in company A who is active in B keeps B's schedule) needs the rule's companyUuid, which the
        // table does not yet carry. TODO (Phase 3, D): store companyUuid and scope this.
        LocalDate termMonth = effectiveTerminationMonth(rule.getUserUuid(), horizonEnd);
        LocalDate ruleEnd = rule.getEffectiveTo() != null ? rule.getEffectiveTo() : FAR_FUTURE;
        LocalDate windowEnd = min(min(ruleEnd, termMonth == null ? FAR_FUTURE : termMonth), horizonEnd);
        boolean cutByTerm = termMonth != null && termMonth.isBefore(ruleEnd);

        List<ProjectedPayout> payouts = new ArrayList<>();

        // 2. Walk each fiscal year overlapping [effectiveFrom, windowEnd].
        int fromFy = fiscalYearStart(rule.getEffectiveFrom()).getYear();
        int toFy = fiscalYearStart(windowEnd).getYear();
        for (int fyYear = fromFy; fyYear <= toFy; fyYear++) {
            LocalDate fyStart = LocalDate.of(fyYear, 7, 1);
            LocalDate fyEnd = LocalDate.of(fyYear + 1, 6, 30);
            LocalDate empFrom = max(rule.getEffectiveFrom(), fyStart);
            LocalDate empTo = min(windowEnd, fyEnd);
            if (empFrom.isAfter(empTo)) continue;

            int monthsInFy = basisResolver.monthsActive(rule.getUserUuid(), empFrom, empTo);
            boolean estimated = empTo.isAfter(LocalDate.now()); // window not fully settled yet
            BigDecimal earned = earnedFor(spec, rule.getUserUuid(), empFrom, empTo, monthsInFy);

            // A yearly/true-up payout for a terminated FY settles at the termination month, not FY-end,
            // so it stays inside the window (otherwise it would be filtered out below).
            boolean fyCutByTerm = termMonth != null
                    && !termMonth.isBefore(fyStart) && !termMonth.isAfter(fyEnd);
            LocalDate yearMonth = fyCutByTerm ? termMonth : yearlyPayMonth(fyEnd, spec);

            switch (spec.schedule().cadence()) {
                case YEARLY -> payouts.add(new ProjectedPayout(
                        yearMonth, earned, PayoutKind.YEARLY, PayoutStatus.PROJECTED,
                        refYearly(rule, fyYear), estimated, fyCutByTerm));

                case MONTHLY -> {
                    for (YearMonth m : employedMonths(empFrom, empTo)) {
                        payouts.add(new ProjectedPayout(
                                m.atDay(1), monthlyAmount(spec, earned, monthsInFy),
                                PayoutKind.MONTHLY, PayoutStatus.PROJECTED,
                                refAdvance(rule, m), estimated, false));
                    }
                }

                case MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP -> {
                    BigDecimal advancesPaid = BigDecimal.ZERO;
                    for (YearMonth m : employedMonths(empFrom, empTo)) {
                        BigDecimal projected = advanceAmount(spec, earned, monthsInFy);
                        // Reconcile the true-up against what was ACTUALLY paid: once a month's advance
                        // lump sum is materialised its committed amount is authoritative (matters for
                        // PERCENT_OF_PROJECTED and mid-year estimate changes); else use the projection.
                        BigDecimal committed = committedAmount(refAdvance(rule, m));
                        advancesPaid = advancesPaid.add(committed != null ? committed : projected);
                        payouts.add(new ProjectedPayout(
                                m.atDay(1), projected, PayoutKind.ADVANCE, PayoutStatus.PROJECTED,
                                refAdvance(rule, m), estimated, false));
                    }
                    // FY-close true-up (or FINAL_SETTLEMENT on termination) = earned(-to-termination)
                    // − Σ advances-paid; the termination path reconciles against advances actually paid.
                    BigDecimal trueUp = reconcileTrueUp(spec, earned, advancesPaid);
                    PayoutKind kind = fyCutByTerm ? PayoutKind.FINAL_SETTLEMENT : PayoutKind.TRUEUP;
                    payouts.add(new ProjectedPayout(
                            yearMonth, trueUp, kind, PayoutStatus.PROJECTED,
                            refTrueUp(rule, fyYear), estimated, fyCutByTerm));
                }
            }
        }

        // 3. Drop anything past the window; overlay committed lump sums.
        return payouts.stream()
                .filter(p -> !p.month().isAfter(windowEnd))
                .map(this::overlayCommitted)
                .sorted(Comparator.comparing(ProjectedPayout::month))
                .toList();
    }

    // --- amount helpers ---

    private BigDecimal earnedFor(Spec spec, String userUuid, LocalDate from, LocalDate to, int months) {
        if (spec.basis() == Basis.FIXED_AMOUNT) return BigDecimal.ZERO; // amount comes from the schedule
        BigDecimal basisAmount = basisResolver.resolveBasisAmount(spec.basis(), userUuid, from, to);
        BigDecimal earned = evaluator.computeEarned(spec.tierTable(), basisAmount, spec.proRating(), months);
        if (spec.cap() != null && earned.compareTo(spec.cap()) > 0) earned = spec.cap();
        return earned;
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
     * The first month with no pay if the user is terminated as of {@code horizonEnd}, else null.
     * Mirrors {@code User.getUserStatus} (latest status wins; TERMINATED wins same-date ties).
     */
    private LocalDate effectiveTerminationMonth(String userUuid, LocalDate horizonEnd) {
        List<UserStatus> statuses = UserStatus.findByUseruuid(userUuid);
        Optional<UserStatus> latest = statuses.stream()
                .filter(s -> s.getStatusdate() != null && !s.getStatusdate().isAfter(horizonEnd))
                .max(Comparator.comparing(UserStatus::getStatusdate)
                        .thenComparing(s -> s.getStatus() == StatusType.TERMINATED ? 0 : 1));
        if (latest.isPresent() && latest.get().getStatus() == StatusType.TERMINATED) {
            return latest.get().getStatusdate().withDayOfMonth(1);
        }
        return null;
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
