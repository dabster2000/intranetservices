package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle of one calendar hold (plan §8.1). Hold integrity is polling
 * reconciliation (no Graph webhooks): a hold the owner deleted by hand
 * surfaces as MISSING on the next reconciliation sweep, minutes later.
 */
public enum CalendarHoldStatus {
    /** Row exists; the Graph event may or may not have been created yet
     * ({@code graph_event_id} null until the outbox action succeeds). */
    CREATED,
    /** The reconciliation sweep confirmed the event still exists. */
    VERIFIED,
    /** Graph answered 404 for the event — the owner deleted the hold. */
    MISSING,
    /** Terminal: the hold's Graph event was deleted by us (or confirmed
     * already gone). */
    RELEASED
}
