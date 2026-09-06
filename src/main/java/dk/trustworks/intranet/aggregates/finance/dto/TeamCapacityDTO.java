package dk.trustworks.intranet.aggregates.finance.dto;

import dk.trustworks.intranet.aggregates.finance.dto.HourlyOverviewDTO.PlanCoverage;

import java.time.LocalDate;
import java.util.List;

/**
 * Capacity tab for an hourly team (JK Team 2.0 WP6 §4.6.0 "Utilization → Capacity", §4.6.4):
 * declared vs registered per member per ISO week, the capacity split (client paid / client
 * 0 kr / internal approved / unplanned) and the plan-coverage strip (D7).
 *
 * <p>{@code declaredHours} is null for a week with no declaration on any working day —
 * rendered as <em>No plan</em>, never as 0 (D7: an undeclared junior day is 0 h in the
 * numbers, but the surface must say so).
 */
public record TeamCapacityDTO(
        LocalDate from,
        LocalDate to,
        List<LocalDate> weekStarts,
        PlanCoverage coverage,
        List<MemberCapacity> members
) {

    public record MemberCapacity(
            String userId,
            String firstname,
            String lastname,
            List<WeekCell> weeks
    ) {}

    public record WeekCell(
            LocalDate weekStart,
            /** Sum of declared hours over the week's working days; null when nothing is declared */
            Double declaredHours,
            /** Working days in the week with no declaration */
            int undeclaredDays,
            double registeredHours,
            double paidClientHours,
            double zeroRateClientHours,
            double internalHours,
            double otherHours,
            /** fact_budget_day — contract demand */
            double contractBudgetHours,
            /** fact_internal_budget_day — approved internal demand (WP3) */
            double internalBudgetHours,
            /** max(declared − contract budget − internal budget, 0): capacity nobody has claimed */
            double unplannedHours
    ) {}
}
