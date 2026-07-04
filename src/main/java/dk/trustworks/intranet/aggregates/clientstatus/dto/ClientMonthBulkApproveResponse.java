package dk.trustworks.intranet.aggregates.clientstatus.dto;

import java.util.List;

/** Result of a bulk-approve run: how many cells were approved and for which clients. */
public record ClientMonthBulkApproveResponse(
        String month,               // "YYYYMM"
        String scope,               // FULL_ONLY | ALL_REMAINING
        int approvedCount,
        List<String> clientNames    // names of the clients whose cells were approved
) {}
