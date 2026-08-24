package dk.trustworks.intranet.vacationservice.dto;

import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;

import java.time.LocalDate;

/**
 * One (ferieår, pool) balance. {@code usedConfirmed} = Danløn baseline +
 * payroll-stamped registrations after the baseline; {@code usedPending} =
 * registered, awaiting a salary run; {@code usedPlanned} = future-dated.
 */
public record VacationPoolDTO(
        int ferieaar,
        String label,
        VacationPoolType pool,
        LocalDate windowStart,
        LocalDate windowEndEarning,
        LocalDate windowEndUse,
        boolean open,
        LocalDate baselineAsOf,
        double baselineEarned,
        double baselineUsed,
        double accrued,
        double earnedToDate,
        double projectedEarnedTotal,
        double transferredIn,
        double transferredOut,
        double paidOutDays,
        double adjustment,
        double usedConfirmed,
        double usedPending,
        double usedPlanned,
        double usedTotal,
        double remaining,
        double remainingProjected,
        double transferableNow) {
}
