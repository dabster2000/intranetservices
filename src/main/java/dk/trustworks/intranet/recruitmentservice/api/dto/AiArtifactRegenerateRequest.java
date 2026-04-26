package dk.trustworks.intranet.recruitmentservice.api.dto;

/**
 * Request body for {@code POST /api/recruitment/ai-artifacts/{uuid}/regenerate}
 * (spec §6.9 — force a fresh AI attempt).
 *
 * <p>The {@code reason} is folded into the new artifact's input map (alongside
 * a regen counter and timestamp) so the digest differs from the previous
 * attempt and the LLM call is not short-circuited by the digest cache. The
 * field is optional — a null/blank reason is treated as no reason.
 *
 * <p>The endpoint returns the <strong>new</strong> artifact (different UUID,
 * state PENDING). The previous artifact is left untouched as an audit trail.
 */
public record AiArtifactRegenerateRequest(String reason) {
}
