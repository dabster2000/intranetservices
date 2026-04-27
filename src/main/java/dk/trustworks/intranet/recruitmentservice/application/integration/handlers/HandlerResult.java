package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

/**
 * Result returned by an {@link OutboxHandler}. Carries the outcome plus an
 * optional human-readable error string that the worker writes to
 * {@code recruitment_external_outbox.last_error} on retryable / terminal cases.
 */
public record HandlerResult(HandlerOutcome outcome, String error) {

    public static HandlerResult ok() {
        return new HandlerResult(HandlerOutcome.OK, null);
    }

    public static HandlerResult retryable(String error) {
        return new HandlerResult(HandlerOutcome.RETRYABLE, error);
    }

    public static HandlerResult terminal(String error) {
        return new HandlerResult(HandlerOutcome.TERMINAL, error);
    }
}
