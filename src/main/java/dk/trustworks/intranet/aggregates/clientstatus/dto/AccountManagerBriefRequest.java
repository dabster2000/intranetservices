package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Request for the Account-Manager invoicing-status brief.
 *
 * @param accountManagerUuid   required; the AM's user UUID (validated against the UUID pattern)
 * @param end                  optional "YYYYMM"; TTM window end (defaults to current month)
 * @param framing              optional: "TO_AM" (default) or "SELF" (self-review variant)
 * @param hideMinorAnomalies   optional (default false): the AI omits minor deviations and
 *                             bagatelle observations, keeping only material amounts and clear patterns
 * @param hideShiftedInvoicing optional (default false): the AI drops gaps it can trace with
 *                             reasonably high confidence to invoicing in an earlier/later month
 */
public record AccountManagerBriefRequest(
        String accountManagerUuid,
        String end,
        String framing,
        Boolean hideMinorAnomalies,
        Boolean hideShiftedInvoicing
) {}
