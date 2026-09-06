package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The hourly-kind block of the team overview (JK Team 2.0 WP6 §4.6.0, tab matrix row
 * "Overview / HOURLY"). Present only when the team resolves to {@code HOURLY}; every
 * number here is built on declared hours (WP1), the client-hours discriminator (§4.4.0)
 * and the HOURLY salary branch — never on the CONSULTANT-only queries.
 */
public record HourlyOverviewDTO(
        /** D12 headcount split from the explicit study_level field */
        int bachelorCount,
        int kandidatCount,
        int unknownLevelCount,
        /** D7: who has a plan for the next horizon — the leads' most important number */
        PlanCoverage planCoverage,
        /** Client hours in the reporting month, paid vs 0 kr, plus internal */
        ClientHours clientHoursMonth,
        JkProfitTotals jkProfitMonth,
        JkProfitTotals jkProfitFy
) {

    public record PlanCoverage(
            int declaredCount,
            int total,
            int horizonWeeks,
            LocalDate horizonFrom,
            LocalDate horizonTo,
            /** Members with at least one undeclared working day in the horizon */
            List<NameRef> missing
    ) {}

    public record ClientHours(
            String monthKey,
            double paidHours,
            double zeroRateHours,
            double internalHours
    ) {}

    /**
     * JK Profit (§4.6.2 row 2.2.1): revenue = paid client hours × rate; cost = the HOURLY
     * branch of fact_salary_monthly, already paid hours × hourly rate, normalised to DKK.
     * {@code valueGivenAway} is 0 kr hours × list rate — shown beside profit, never in it.
     */
    public record JkProfitTotals(
            String label,
            double revenue,
            double cost,
            double profit,
            double paidHours,
            double zeroRateHours,
            double valueGivenAway
    ) {
        public static JkProfitTotals of(String label, double revenue, double cost,
                                        double paidHours, double zeroRateHours, double valueGivenAway) {
            return new JkProfitTotals(label, revenue, cost, revenue - cost, paidHours, zeroRateHours, valueGivenAway);
        }
    }
}
