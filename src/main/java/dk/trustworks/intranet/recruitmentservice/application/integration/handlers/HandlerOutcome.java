package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

/**
 * Outcome of a single dispatch attempt for an outbox row. The worker maps these
 * onto status transitions: OK → DONE, RETRYABLE → PENDING (with backoff),
 * TERMINAL → FAILED.
 */
public enum HandlerOutcome {
    OK,
    RETRYABLE,
    TERMINAL
}
