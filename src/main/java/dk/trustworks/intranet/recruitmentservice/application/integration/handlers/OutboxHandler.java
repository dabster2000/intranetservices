package dk.trustworks.intranet.recruitmentservice.application.integration.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;

/**
 * Strategy contract for a single dispatch kind. Implementations are stateless
 * CDI beans; the {@code OutboxDispatcher} routes rows to the handler matching
 * the row's {@link dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind}.
 *
 * <p>Implementations must be defensive: any thrown exception is treated by the
 * worker as a retryable failure, but handlers SHOULD return {@link HandlerResult#terminal(String)}
 * for non-recoverable conditions to avoid pointless backoff cycles.
 */
public interface OutboxHandler {
    HandlerResult handle(RecruitmentOutboxRow row);
}
