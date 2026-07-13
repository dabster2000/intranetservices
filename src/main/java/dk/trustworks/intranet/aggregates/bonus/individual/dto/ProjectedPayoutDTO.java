package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.aggregates.bonus.individual.model.FactCoverage;
import dk.trustworks.intranet.aggregates.bonus.individual.model.StepBand;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-time projection of a single (committed or projected) payout, for salary "expected pay" views.
 *
 * @param month                  day-1 of the payout month
 * @param amount                 gross DKK
 * @param kind                   ADVANCE / MONTHLY / YEARLY / TRUEUP / FINAL_SETTLEMENT
 * @param status                 COMMITTED or PROJECTED
 * @param sourceReference        stable idempotency id
 * @param estimated              basis uses forecast, not actuals
 * @param truncatedByTermination schedule was cut here by an early leave
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectedPayoutDTO(
        LocalDate month,
        BigDecimal amount,
        String kind,
        String status,
        String sourceReference,
        boolean estimated,
        boolean truncatedByTermination,
        LocalDate payMonth,
        LocalDate earningMonth,
        String payoutStatus,
        String calculationState,
        BigDecimal utilization,
        StepBand selectedBand,
        BigDecimal employmentFactor,
        BigDecimal baseSalary,
        BigDecimal supplement,
        BigDecimal totalSalary,
        Breakdown breakdown,
        String failureReasonCode,
        ManualAction manualAction,
        String ruleUuid,
        String payoutUuid,
        BigDecimal committedNetAmount,
        String activeAdjustmentUuid
) {
    public ProjectedPayoutDTO(LocalDate month, BigDecimal amount, String kind, String status,
                              String sourceReference, boolean estimated, boolean truncatedByTermination) {
        this(month, amount, kind, status, sourceReference, estimated, truncatedByTermination,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Breakdown(
            BigDecimal billableHours,
            BigDecimal availableHours,
            BigDecimal grossOverlapHours,
            BigDecimal grossFullMonthHours,
            FactCoverage factCoverage,
            BigDecimal rawUtilization,
            BigDecimal selectionUtilization,
            SalaryGuard salaryGuard
    ) { }

    public record SalaryGuard(BigDecimal expected, BigDecimal effective, String status) { }
    public record ManualAction(String code, String adjustmentUuid, String message) { }
}
