package dk.trustworks.intranet.vacationservice.dto;

import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;

/**
 * Admin posting: PAYOUT (positive days, subtracts) or ADJUSTMENT (signed).
 * Transfers use the dedicated transfer endpoint so the ferie/feriefridage
 * split stays rule-driven.
 */
public record ManualVacationEntryRequest(
        int ferieaar,
        VacationPoolType pool,
        String entryType,
        double days,
        String note) {
}
