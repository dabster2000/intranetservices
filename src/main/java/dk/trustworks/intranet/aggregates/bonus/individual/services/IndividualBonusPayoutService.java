package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Advance;
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
import java.util.Optional;

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
     * Materialise all payouts due in {@code month}. Idempotent and safe to re-run.
     *
     * @return number of rows (lump sums or PREPAID supplements) newly materialised
     */
    @Transactional
    public int materializeDue(LocalDate month) {
        LocalDate payMonth = month.withDayOfMonth(1);
        LocalDate horizon = payMonth.withDayOfMonth(payMonth.lengthOfMonth());
        int created = 0;

        List<IndividualBonusRule> rules = IndividualBonusRule.list("active = true");
        for (IndividualBonusRule rule : rules) {
            Spec spec = specMapper.parse(rule.getSpec());
            boolean prepaidVehicle = isPrepaidSupplement(spec);

            // A PREPAID_SUPPLEMENT advance is delivered by ONE recurring SalarySupplement (which projects
            // forward and trims its own toMonth), not by monthly lump sums. Maintain it BEFORE the ACTIVE
            // gate so a termination this month still trims the supplement's window.
            if (prepaidVehicle && maintainPrepaidSupplement(rule, spec, payMonth)) {
                created++;
            }

            // Belt-and-braces: never write pay for a month the employee is not ACTIVE (a leaver's
            // future advances are simply never written); the Danløn export is the final backstop.
            if (!isActiveInMonth(rule.getUserUuid(), payMonth)) continue;

            for (ProjectedPayout p : scheduleService.project(rule, horizon)) {
                if (!p.month().isEqual(payMonth)) continue;
                if (!shouldMaterialize(p)) continue;
                // PREPAID_SUPPLEMENT: monthly advances are the supplement's job — only the year-end
                // true-up / final settlement is still written as a lump sum.
                if (prepaidVehicle && (p.kind() == PayoutKind.ADVANCE || p.kind() == PayoutKind.MONTHLY)) {
                    continue;
                }
                try {
                    // Each write runs in its own REQUIRES_NEW transaction (via the injected writer, a
                    // CDI proxy) so a unique-constraint race on source_reference rolls back only THIS
                    // payout, not the whole batch.
                    if (lumpSumWriter.writeIfAbsent(rule.getUserUuid(), p.amount(), payMonth,
                            rule.getName(), p.sourceReference(), Boolean.TRUE.equals(spec.pension()))) {
                        created++;
                    }
                } catch (RuntimeException e) {
                    // A lost race (concurrent scheduled job + manual run, or ECS-Express double-fire) is
                    // benign and idempotent — log and continue so one payout never aborts the batch.
                    log.warnf("Skipped individual-bonus payout %s for %s (already materialised or write failed): %s",
                            p.sourceReference(), rule.getUserUuid(), e.getMessage());
                }
            }
        }
        log.infof("Individual bonus materialisation for %s: %d row(s) created", payMonth, created);
        return created;
    }

    /**
     * Whether a projected payout should be written as a lump sum. Zero is always a no-op; a NEGATIVE
     * amount is written ONLY for a year-end true-up / final settlement (a CLAWBACK deduction → a negative
     * Danløn '41' line). ADVANCE / MONTHLY / YEARLY never go negative. Pure and package-visible so the
     * gate is unit-testable without booting Quarkus.
     */
    static boolean shouldMaterialize(ProjectedPayout p) {
        if (p == null || p.amount() == null) return false;
        int sign = p.amount().signum();
        if (sign == 0) return false;                 // zero is always skipped
        if (sign > 0) return true;                   // positive is always written
        return p.kind() == PayoutKind.TRUEUP || p.kind() == PayoutKind.FINAL_SETTLEMENT;
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

    /** Effective employment status at the last day of {@code month} is ACTIVE (company-agnostic). */
    private boolean isActiveInMonth(String userUuid, LocalDate month) {
        LocalDate asOf = month.withDayOfMonth(month.lengthOfMonth());
        List<UserStatus> statuses = UserStatus.findByUseruuid(userUuid);
        Optional<UserStatus> effective = statuses.stream()
                .filter(s -> s.getStatusdate() != null && !s.getStatusdate().isAfter(asOf))
                .max(Comparator.comparing(UserStatus::getStatusdate)
                        .thenComparing(s -> s.getStatus() == StatusType.TERMINATED ? 0 : 1));
        return effective.isPresent() && effective.get().getStatus() == StatusType.ACTIVE;
    }
}
