package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDateTime;

/** One band change, with the person who made it resolved to a name. */
public record BandHistoryDTO(
        String uuid,
        String fromBand,
        String toBand,
        String note,
        PersonDTO changedBy,
        LocalDateTime changedAt) {
}
