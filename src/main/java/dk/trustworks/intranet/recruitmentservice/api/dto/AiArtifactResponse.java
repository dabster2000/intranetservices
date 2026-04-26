package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for {@link AiArtifact} returned by
 * {@code GET /api/recruitment/ai-artifacts/{uuid}} (spec §6.9).
 *
 * <p>Carries the full review surface the front-end needs: the raw {@code output}
 * JSON (parsed client-side based on {@code kind}), the {@code evidence} JSON,
 * confidence score, state, and audit timestamps. {@code overrideJson} is populated
 * only after a reviewer edits or discards the artifact (state = OVERRIDDEN).
 *
 * <p>The {@code state} field is exposed as a String to mirror the entity column
 * (spec §6.9 enum: NOT_GENERATED, GENERATING, GENERATED, REVIEWED, OVERRIDDEN, FAILED).
 * The {@code kind} is also a String so we don't leak {@link
 * dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind} stability
 * concerns into the contract — the FE matches on the literal string.
 *
 * <p>{@code inputDigest} is intentionally omitted: it's an internal idempotency key,
 * not part of the review contract.
 */
public record AiArtifactResponse(
        String uuid,
        AiSubjectKind subjectKind,
        String subjectUuid,
        String kind,
        String promptVersion,
        String model,
        String state,
        String output,
        String evidence,
        BigDecimal confidence,
        LocalDateTime generatedAt,
        String reviewedByUuid,
        LocalDateTime reviewedAt,
        String overrideJson) {

    public static AiArtifactResponse from(AiArtifact a) {
        return new AiArtifactResponse(
                a.uuid,
                a.subjectKind,
                a.subjectUuid,
                a.kind,
                a.promptVersion,
                a.model,
                a.state,
                a.output,
                a.evidence,
                a.confidence,
                a.generatedAt,
                a.reviewedByUuid,
                a.reviewedAt,
                a.overrideJson);
    }
}
