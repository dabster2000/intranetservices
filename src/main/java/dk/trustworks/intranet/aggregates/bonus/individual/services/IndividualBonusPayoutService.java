package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaException;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Advance;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MaterializedPayoutCommand;
import dk.trustworks.intranet.aggregates.bonus.individual.model.PayoutKind;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProjectedPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Vehicle;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.fiscalYearStart;

/**
 * LAYER 2 · MATERIALIZATION — writes rows just-in-time.
 * <p>
 * For a due month, projects each active rule and writes the payouts falling in that month as
 * {@code INDIVIDUAL_PROD_BONUS} lump sums (Danløn type 41), but ONLY if the employee is ACTIVE that
 * month. Each write goes through {@link IndividualBonusLumpSumWriter} in its own REQUIRES_NEW
 * transaction and is idempotent via {@code salary_lump_sum.source_reference} (unique index), so a
 * concurrent run / ECS-Express double-fire — or a re-run after partial materialisation — cannot
 * double-pay and cannot roll back the rest of the batch.
 * <p>
 * Amounts are GROSS (D3); A-skat / AM-bidrag are withheld by Danløn downstream.
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusPayoutService {

    @Inject IndividualBonusScheduleService scheduleService;
    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusLumpSumWriter lumpSumWriter;
    @Inject IndividualBonusSupplementWriter supplementWriter;

    /**
     * Admin-triggered materialisation ({@code /payouts/run}): writes EVERY due kind (monthly advances AND
     * the high-materiality YEARLY / TRUEUP / FINAL_SETTLEMENT settlements). Idempotent and safe to re-run.
     *
     * @return number of rows (lump sums or PREPAID supplements) newly materialised
     */
    @Transactional
    public int materializeDue(LocalDate month) {
        return materializeDue(month, false);
    }

    /**
     * Materialise payouts due in {@code month}.
     *
     * @param scheduledOnly when true (the monthly {@code @Scheduled} job) ONLY the mechanical monthly
     *   ADVANCE / MONTHLY pay — plus PREPAID supplement maintenance — is written; the high-materiality
     *   YEARLY / TRUEUP / FINAL_SETTLEMENT settlements stay ADMIN-CONFIRMED (spec §5) and are skipped.
     *   The {@code /payouts/run} endpoint passes false (admin = all kinds). Idempotent and safe to re-run.
     * @return number of rows (lump sums or PREPAID supplements) newly materialised
     */
    @Transactional
    public int materializeDue(LocalDate month, boolean scheduledOnly) {
        LocalDate payMonth = month.withDayOfMonth(1);
        LocalDate horizon = payMonth.withDayOfMonth(payMonth.lengthOfMonth());
        int created = 0;

        List<IndividualBonusRule> rules = IndividualBonusRule.list("active = true");
        for (IndividualBonusRule rule : rules) {
            // MONEY-SAFE BOUNDARY: a formula that fails to evaluate (a runtime error, timeout/runaway, or a
            // null / non-numeric result) must NOT abort the whole batch and must NEVER pay a guessed amount.
            // Skip just THIS rule with a prominent WARN for manual handling (mirroring the CLAWBACK
            // "flag for manual handling" pattern); every other rule still materialises.
            try {
                created += materializeRuleForMonth(rule, payMonth, horizon, scheduledOnly);
            } catch (BonusFormulaException e) {
                log.warnf("MANUAL ACTION REQUIRED — individual-bonus formula evaluation failed for rule %s "
                                + "(user %s, %s); NO payout auto-written this run: %s",
                        rule.getUuid(), rule.getUserUuid(), payMonth, e.getMessage());
            }
        }
        log.infof("Individual bonus materialisation for %s (scheduledOnly=%b): %d row(s) created",
                payMonth, scheduledOnly, created);
        return created;
    }

    /**
     * Materialise the payouts due in {@code payMonth} for ONE rule and return how many rows were written.
     * A {@link BonusFormulaException} propagates to {@link #materializeDue}, which isolates the failure to
     * this rule (skip + WARN) so one bad formula neither aborts the batch nor pays a guessed amount.
     */
    private int materializeRuleForMonth(IndividualBonusRule rule, LocalDate payMonth, LocalDate horizon,
                                        boolean scheduledOnly) {
        int created = 0;
        Spec spec = specMapper.parse(rule.getSpec());
        boolean prepaidVehicle = isPrepaidSupplement(spec);

        // A PREPAID_SUPPLEMENT advance is delivered by ONE recurring SalarySupplement (which projects
        // forward and trims its own toMonth), not by monthly lump sums. Maintain it BEFORE the ACTIVE
        // gate so a termination this month still trims the supplement's window.
        if (prepaidVehicle && maintainPrepaidSupplement(rule, spec, payMonth)) {
            created++;
        }

        boolean activeThisMonth = isActiveInMonth(rule.getUserUuid(), payMonth);

        // Settlements are reconciled against advances ACTUALLY PAID (committed lump sums only), and each
        // payout carries the basis inputs frozen into the reproducibility snapshot.
        for (IndividualBonusScheduleService.PayoutWithInputs pi
                : scheduleService.projectWithInputs(rule, horizon, true)) {
            ProjectedPayout p = pi.payout();
            if (!p.month().isEqual(payMonth)) continue;

            // (§5) The monthly job writes only the mechanical advances; settlements are admin-confirmed.
            if (scheduledOnly && !IndividualBonusScheduleService.isMonthlyAdvanceKind(p.kind())) continue;

            // The ACTIVE gate applies ONLY to recurring advance/monthly pay (a leaver's future advances
            // are never written). A YEARLY / TRUEUP / FINAL_SETTLEMENT still settles for a terminated
            // employee — it lands in the leave month, when the employee is no longer ACTIVE.
            if (IndividualBonusScheduleService.isMonthlyAdvanceKind(p.kind()) && !activeThisMonth) continue;

            // PREPAID_SUPPLEMENT delivers monthly advances via a recurring supplement, not lump sums;
            // only the year-end true-up / final settlement is still written as a lump sum.
            if (prepaidVehicle && IndividualBonusScheduleService.isMonthlyAdvanceKind(p.kind())) continue;

            // A net-negative settlement is a CLAWBACK. The Danløn export drops a net-negative "41 Bonus"
            // row entirely (DanlonResource emits the line only when the month's sum > 0) and Danløn
            // løntype 41 cannot carry a negative, so we do NOT auto-write it — HR settles the deduction
            // manually. The projection still SHOWS the negative for visibility.
            if (isNegativeClawback(p)) {
                log.warnf("MANUAL ACTION REQUIRED — individual-bonus CLAWBACK of %s for user %s not "
                                + "auto-paid (%s, %s): Danløn cannot export a negative løntype-41 line; "
                                + "settle it as a manual deduction.",
                        p.amount(), rule.getUserUuid(), p.sourceReference(), payMonth);
                continue;
            }
            if (!shouldMaterialize(p)) continue;

            try {
                // Each write runs in its own REQUIRES_NEW transaction (via the injected writer, a CDI
                // proxy) so a unique-constraint race on source_reference rolls back only THIS payout —
                // and its snapshot — not the whole batch.
                boolean wrote = lumpSumWriter.writeIfAbsent(new MaterializedPayoutCommand(
                        rule.getUuid(), rule.getUserUuid(), rule.getName(), payMonth, p.kind(), p.amount(),
                        Boolean.TRUE.equals(spec.pension()), p.sourceReference(),
                        rule.getSpec(), pi.basisAmount(), pi.monthsEmployed()));
                if (wrote) created++;
            } catch (BonusFormulaException e) {
                // A formula failure must bubble to the per-rule boundary in materializeDue (skip + WARN),
                // never be swallowed as a benign write race below.
                throw e;
            } catch (RuntimeException e) {
                // A lost race (concurrent scheduled job + manual run, or ECS-Express double-fire) is
                // benign and idempotent — log and continue so one payout never aborts the batch.
                log.warnf("Skipped individual-bonus payout %s for %s (already materialised or write failed): %s",
                        p.sourceReference(), rule.getUserUuid(), e.getMessage());
            }
        }
        return created;
    }

    /**
     * A net-negative FY settlement (a CLAWBACK deduction): NOT auto-written — surfaced as a WARN for manual
     * handling because Danløn cannot export a negative løntype-41 line. Pure and package-visible.
     */
    static boolean isNegativeClawback(ProjectedPayout p) {
        return p != null && p.amount() != null && p.amount().signum() < 0
                && IndividualBonusScheduleService.isSettlementKind(p.kind());
    }

    /**
     * Whether a projected payout is auto-written as a lump sum: ONLY strictly-positive amounts. Zero is a
     * no-op; a NEGATIVE (CLAWBACK) settlement is deliberately NOT auto-paid (see {@link #isNegativeClawback})
     * — Danløn cannot export a negative løntype-41 line — so it is surfaced as a WARN and still shown in the
     * projection. Pure and package-visible so the gate is unit-testable without booting Quarkus.
     */
    static boolean shouldMaterialize(ProjectedPayout p) {
        return p != null && p.amount() != null && p.amount().signum() > 0;
    }

    private static boolean isPrepaidSupplement(Spec spec) {
        Advance advance = spec.schedule() != null ? spec.schedule().advance() : null;
        return advance != null && advance.vehicle() == Vehicle.PREPAID_SUPPLEMENT;
    }

    /**
     * Maintain the single recurring PREPAID SalarySupplement that delivers this rule's fixed monthly
     * advance for the fiscal year containing {@code payMonth}. The window ({@code fromMonth..toMonth}) is
     * taken from the termination- and effective-window-aware projection (validated to be FIXED-only at
     * write time), so it trims automatically on an early leave. Idempotent (find-or-update by marker).
     *
     * @return true if a NEW supplement was created (false if an existing one was updated / nothing to do)
     */
    private boolean maintainPrepaidSupplement(IndividualBonusRule rule, Spec spec, LocalDate payMonth) {
        Advance advance = spec.schedule().advance();
        if (advance == null || advance.fixedAmountPerMonth() == null) return false;

        int fyYear = fiscalYearStart(payMonth).getYear();
        LocalDate fyStart = LocalDate.of(fyYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fyYear + 1, 6, 30);

        // Reuse the projection's window + termination truncation to size the supplement: fromMonth =
        // first employed advance month in this FY, toMonth = last (already cut at an early leave).
        List<ProjectedPayout> advanceMonths = scheduleService.project(rule, fyEnd).stream()
                .filter(p -> p.kind() == PayoutKind.ADVANCE || p.kind() == PayoutKind.MONTHLY)
                .filter(p -> !p.month().isBefore(fyStart) && !p.month().isAfter(fyEnd))
                .sorted(Comparator.comparing(ProjectedPayout::month))
                .toList();
        if (advanceMonths.isEmpty()) return false;

        LocalDate fromMonth = advanceMonths.get(0).month();
        LocalDate toMonth = advanceMonths.get(advanceMonths.size() - 1).month();
        try {
            return supplementWriter.upsertPrepaidSupplement(
                    rule.getUserUuid(), advance.fixedAmountPerMonth(), fromMonth, toMonth, rule.getName(),
                    IndividualBonusSupplementWriter.sourceRef(rule.getUuid(), fyYear),
                    Boolean.TRUE.equals(spec.pension()));
        } catch (RuntimeException e) {
            // Lost a race on the unique salary_supplement.source_reference index — a concurrent run already
            // created this rule's PREPAID supplement for the FY (the winner carries this month's value /
            // window; a later run reconciles any change). Benign — never abort the batch.
            log.warnf("Skipped PREPAID supplement for %s (already materialised or write failed): %s",
                    rule.getUserUuid(), e.getMessage());
            return false;
        }
    }

    /**
     * Effective employment status at the last day of {@code month} is ACTIVE (company-agnostic). Shares the
     * TERMINATED-wins-same-date tie-break with {@link IndividualBonusScheduleService#effectiveStatusAsOf}.
     */
    private boolean isActiveInMonth(String userUuid, LocalDate month) {
        LocalDate asOf = month.withDayOfMonth(month.lengthOfMonth());
        return IndividualBonusScheduleService.effectiveStatusAsOf(UserStatus.findByUseruuid(userUuid), asOf)
                .map(s -> s.getStatus() == StatusType.ACTIVE)
                .orElse(false);
    }
}
