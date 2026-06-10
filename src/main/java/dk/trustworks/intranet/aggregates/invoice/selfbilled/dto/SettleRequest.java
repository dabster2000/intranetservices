package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/** Settle one (client, consultant, work-period). queue=true -> QUEUED doc (never auto-posts, spec §9). */
public record SettleRequest(String clientUuid, String consultantUuid,
                            int workYear, int workMonth, boolean queue) {}
