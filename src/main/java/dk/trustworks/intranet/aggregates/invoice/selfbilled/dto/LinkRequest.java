package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/** Human link of a pre-existing internal to its (client, consultant, work-period) group (spec §6.2). */
public record LinkRequest(String clientUuid, String consultantUuid, int workYear, int workMonth) {}
