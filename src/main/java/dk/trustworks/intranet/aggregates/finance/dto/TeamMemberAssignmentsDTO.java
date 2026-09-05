package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Capacity & assignments card for one member (JK Team 2.0 WP6 §4.6.4, KPC tab / hourly kind):
 * every contract line and internal assignment touching the window, and the declared-vs-actual
 * deviation per month (WP1 §4.1.4). Multiple concurrent lines are supported by construction.
 */
public record TeamMemberAssignmentsDTO(
        String userId,
        LocalDate from,
        LocalDate to,
        List<ContractLine> contracts,
        List<InternalLine> internalAssignments,
        List<MonthCapacity> months
) {

    public record ContractLine(
            String consultantLineUuid,
            String contractUuid,
            String contractName,
            String clientName,
            String accountManagerName,
            LocalDate activeFrom,
            LocalDate activeTo,
            double rate,
            double hoursPerWeek,
            /** hoursPerWeek ÷ 37 — the line's share of a full week */
            int allocationPercent,
            String pricingModelCode,
            boolean zeroRate,
            String zeroRateReason,
            LocalDate rateReviewDate,
            Double listRate,
            String status,
            String notes
    ) {}

    public record InternalLine(
            String uuid,
            String title,
            String sponsorName,
            LocalDate activeFrom,
            LocalDate activeTo,
            double hoursPerWeek,
            boolean strategic,
            String status
    ) {}

    /**
     * @param declaredHours       null when no day in the month is declared (No plan)
     * @param deviationHours      registered − declared; null when undeclared
     */
    public record MonthCapacity(
            String monthKey,
            Double declaredHours,
            int undeclaredWorkingDays,
            double registeredHours,
            double paidClientHours,
            double zeroRateClientHours,
            double internalHours,
            double contractBudgetHours,
            double internalBudgetHours,
            Double deviationHours
    ) {}
}
