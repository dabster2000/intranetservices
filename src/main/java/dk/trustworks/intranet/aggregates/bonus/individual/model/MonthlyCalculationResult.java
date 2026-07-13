package dk.trustworks.intranet.aggregates.bonus.individual.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Complete read-only result for one calendar earning month. */
public record MonthlyCalculationResult(
        YearMonth earningMonth,
        YearMonth payMonth,
        List<EmploymentSegment> employmentSegments,
        LocalDate overlapStart,
        LocalDate overlapEnd,
        String earningCompanyUuid,
        CalculationState calculationState,
        PayoutLifecycleStatus payoutStatus,
        BigDecimal finalSupplement,
        BigDecimal expectedBaseSalary,
        BigDecimal effectiveBaseSalary,
        BigDecimal displayedTotalSalary,
        UtilizationResolution utilization,
        BigDecimal selectionUtilization,
        StepBand selectedBand,
        BigDecimal grossOverlapHours,
        BigDecimal grossFullMonthHours,
        BigDecimal employmentFactor,
        BigDecimal unroundedSupplement,
        String blockerCode
) {
    public boolean hasEarningOverlap() {
        return employmentSegments != null && !employmentSegments.isEmpty();
    }

    public boolean materializable() {
        return calculationState == CalculationState.ACTUAL
                && payoutStatus != PayoutLifecycleStatus.BLOCKED
                && finalSupplement != null;
    }
}
