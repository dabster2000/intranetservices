package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle of one outbox action (plan §8.3, invoice-booking idiom).
 */
public enum SchedulingOutboxStatus {
    /** Enqueued (or re-queued after a failed attempt); due when
     * {@code next_attempt_at} passes. */
    PENDING,
    /** Atomically claimed by one instance; stale claims (crashed mid-
     * execution) become re-eligible after the claim timeout. */
    IN_PROGRESS,
    /** Terminal: the external write succeeded. */
    COMPLETED,
    /** Terminal: attempts exhausted — dead-lettered. Surfaces as a
     * cleanup warning on the request (spec §21.5); a human resolves it. */
    FAILED
}
