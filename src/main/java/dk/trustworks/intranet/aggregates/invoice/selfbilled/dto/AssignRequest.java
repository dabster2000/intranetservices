package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

/**
 * Assign request: REPLACE semantics over the voucher's whole assignment set —
 * 1 entry = assign/edit, N entries = split (must sum to the voucher net, AC3).
 * shareAmount values are normalized positive (UI convention).
 */
public record AssignRequest(List<AssignmentInput> assignments) {}
