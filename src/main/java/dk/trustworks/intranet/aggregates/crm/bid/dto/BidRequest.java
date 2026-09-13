package dk.trustworks.intranet.aggregates.crm.bid.dto;

import java.time.LocalDate;

/**
 * Creating or editing a bid. On a PATCH a null field means "leave it alone"; the
 * {@code clear*} flags exist because a JSON null and an absent key are indistinguishable
 * after deserialisation.
 */
public record BidRequest(
        String clientUuid,
        String leadUuid,
        boolean clearLead,
        String title,
        String type,
        String goNoGo,
        Double price,
        boolean clearPrice,
        LocalDate dueDate,
        String outcome,
        String competitor,
        boolean clearCompetitor,
        String postMortem,
        String ownerUuid) {
}
