package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Request for the Account-Manager invoicing-status brief.
 *
 * @param accountManagerUuid   required; the AM's user UUID (validated against the UUID pattern)
 * @param end                  optional "YYYYMM"; TTM window end (defaults to current month)
 * @param framing              optional: "TO_AM" (default) or "SELF" (self-review variant)
 * @param minorAnomalyFloorDkk optional (default 0, clamped 0..500000): the AI omits points whose
 *                             amount is below this DKK floor; 0 = report everything material
 * @param reportMonths         optional (default 12, clamped 1..12): the message only contains
 *                             points about the last N non-provisional months — the AI still
 *                             receives the full TTM series so it can trace earlier/later invoicing
 * @param hideShiftedInvoicing optional (default false): the AI drops gaps it can trace with
 *                             reasonably high confidence to invoicing in an earlier/later month
 */
public record AccountManagerBriefRequest(
        String accountManagerUuid,
        String end,
        String framing,
        Integer minorAnomalyFloorDkk,
        Integer reportMonths,
        Boolean hideShiftedInvoicing
) {}
