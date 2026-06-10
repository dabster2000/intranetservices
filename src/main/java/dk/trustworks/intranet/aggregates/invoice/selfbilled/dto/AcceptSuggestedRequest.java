package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

/**
 * Optional body for {@code POST /assignments/accept-suggested} (Feature 1b). When present,
 * accept exactly these voucher anchor lines (each re-checked server-side: still same-company
 * AND still UNASSIGNED) instead of the ≥90 auto-sweep. A null/empty body keeps the original
 * no-body ≥90 behaviour unchanged.
 */
public record AcceptSuggestedRequest(List<String> lineUuids) {}
