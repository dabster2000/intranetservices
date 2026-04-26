package dk.trustworks.intranet.recruitmentservice.api.dto;

/**
 * Request body for {@code POST /api/recruitment/ai-artifacts/{uuid}/review}
 * (spec §6.9 — reviewer disposition).
 *
 * <p>Three dispositions are encoded compactly:
 * <ul>
 *   <li>{@code accepted=true} — accept the AI output as-is. Transitions GENERATED → REVIEWED.</li>
 *   <li>{@code accepted=false, overrideJson=<non-blank JSON>} — accept with edits.
 *       Transitions GENERATED → OVERRIDDEN with the supplied override applied.</li>
 *   <li>{@code accepted=false, overrideJson=null|blank} — discard. Transitions
 *       GENERATED → OVERRIDDEN with a {@code {"discarded":true}} marker.</li>
 * </ul>
 *
 * <p>Validation is intentionally light at the DTO level — the service rejects
 * non-GENERATED artifacts with 409, and {@code overrideJson} is opaque to the
 * resource (downstream apply handlers parse it).
 */
public record AiArtifactReviewRequest(
        boolean accepted,
        String overrideJson) {
}
