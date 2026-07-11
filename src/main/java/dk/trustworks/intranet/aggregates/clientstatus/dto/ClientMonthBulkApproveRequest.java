package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Request body for bulk-approving all eligible client-month cells in a single month.
 *
 * @param month "YYYYMM" month to approve
 * @param scope FULL_ONLY (only fully-billed cells) or ALL_REMAINING (every non-approved cell with activity)
 */
public record ClientMonthBulkApproveRequest(
        String month,           // "YYYYMM"
        String scope            // FULL_ONLY | ALL_REMAINING
) {}
