package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.time.LocalDate;

public record IndividualBonusManualSettlementRequest(
        long version,
        LocalDate settlementMonth,
        String externalReference,
        String note
) {
}
