package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;

import java.time.Instant;
import java.util.List;

/**
 * Response of {@code GET /api/recruitment/interviews/{uuid}/integrations/status}.
 *
 * <p>Aggregates the latest Outlook calendar outbox state and all Slack DM outbox
 * rows scoped to a single interview, so the UI can present a "what happened with
 * the calendar invite + DMs" panel without exposing raw outbox internals.
 */
public record IntegrationStatusResponse(
        OutlookStatus outlook,
        List<SlackDmStatus> slackDms
) {

    /**
     * Latest Outlook event outbox state for the interview. {@code state} is one of
     * {@code NONE} (no row), {@code SENT} (DONE), {@code FAILED}, or {@code PENDING}
     * (PENDING / IN_FLIGHT).
     */
    public record OutlookStatus(
            String state,
            String eventId,
            String lastError,
            Instant lastAttemptAt,
            int attemptCount
    ) {}

    /**
     * One Slack DM outbox row for the interview. {@code state} mirrors the same
     * SENT/FAILED/PENDING vocabulary as {@link OutlookStatus}.
     */
    public record SlackDmStatus(
            OutboxKind kind,
            String recipientUuid,
            String state,
            String lastError,
            Instant lastAttemptAt,
            int attemptCount
    ) {}
}
