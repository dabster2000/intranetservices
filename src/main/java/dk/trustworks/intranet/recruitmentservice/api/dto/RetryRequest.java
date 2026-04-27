package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/recruitment/interviews/{uuid}/integrations/retry}.
 *
 * <p>{@code kind} identifies which Outlook outbox row to re-arm; {@code fallback}
 * is reserved for future {@code "cancel-recreate"} semantics on
 * {@link OutboxKind#OUTLOOK_EVENT_UPDATE} retries — null means "no fallback".
 */
public record RetryRequest(@NotNull OutboxKind kind, String fallback) {}
