package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * Outcome of a bulk same-company accept (Feature 1b): {@code accepted} = vouchers placed,
 * {@code skipped} = candidates re-checked server-side and rejected (no longer same-company,
 * already placed, or unknown). Returned by BOTH the no-body ≥90 auto-path and the explicit
 * lineUuids path so the workbench reports a consistent count.
 */
public record AcceptResult(int accepted, int skipped) {}
