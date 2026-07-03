package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Request for the Account-Manager invoicing-status brief.
 *
 * @param accountManagerUuid required; the AM's user UUID (validated against the UUID pattern)
 * @param end                optional "YYYYMM"; TTM window end (defaults to current month)
 * @param framing            optional: "TO_AM" (default) or "SELF" (self-review variant)
 */
public record AccountManagerBriefRequest(
        String accountManagerUuid,
        String end,
        String framing
) {}
