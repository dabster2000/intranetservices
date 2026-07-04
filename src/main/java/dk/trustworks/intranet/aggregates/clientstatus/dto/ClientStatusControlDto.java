package dk.trustworks.intranet.aggregates.clientstatus.dto;

/**
 * Controlling state of one client-month cell: the approval snapshot (if any), the editable note,
 * and whether the snapshot has drifted from the current values. Nullable on the detail response
 * when no control row exists yet for the cell.
 */
public record ClientStatusControlDto(
        String clientUuid,
        String monthKey,                // "YYYYMM"
        boolean approved,               // true when an approval snapshot exists (even if drifted)
        String approvedBy,              // nullable; UUID of the approver
        String approvedByName,          // nullable; CONCAT(user.firstname, ' ', user.lastname)
        String approvedAt,              // nullable; ISO-8601 datetime string
        String note,                    // nullable; the single editable note
        Double approvedExpected,        // nullable; frozen expected value at approval time
        Double approvedInvoiced,        // nullable; frozen invoiced value at approval time
        boolean drift                   // true when an approved snapshot moved > tolerance
) {}
