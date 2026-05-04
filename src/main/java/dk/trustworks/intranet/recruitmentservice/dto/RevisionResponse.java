package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read model for one immutable dossier revision (a single Send action's
 * frozen snapshot). Populated from the {@code candidate_dossier_revisions}
 * row plus its three JSON snapshot columns.
 * <p>
 * <strong>Sensitive-field stripping:</strong> when the response leaves the
 * resource, {@code RecruitmentRevisionResponseFilter} replaces the values of
 * any placeholder keys that match the CPR/salary/pension regex with the
 * literal string {@code "[REDACTED]"} for callers that lack the
 * {@code users:read} scope.
 *
 * @param uuid                       revision UUID
 * @param dossierUuid                parent dossier UUID
 * @param versionNumber              1-based monotonic version per dossier
 * @param kind                       {@code RevisionKind} as a string
 * @param placeholderValuesSnapshot  frozen placeholder map
 * @param signersConfigSnapshot      frozen signer configuration
 * @param appendixFileUuidsSnapshot  frozen appendix list
 * @param pdfArtifactsSnapshot       frozen list of generated/included PDF
 *                                   artifacts (filename + S3 file UUID),
 *                                   nullable for {@code REVIEW_EMAIL} sends
 *                                   that did not store artifacts separately
 * @param signingCaseKey             NextSign case key (only for
 *                                   {@code SIGNATURE} kind)
 * @param recipientEmail             email the revision was dispatched to
 * @param recipientName              optional display name (resolved from
 *                                   candidate, never user-supplied)
 * @param note                       optional free-text note
 * @param createdByUserUuid          UUID of the actor who performed the Send
 * @param createdAt                  when the revision was allocated
 */
public record RevisionResponse(
        String uuid,
        String dossierUuid,
        int versionNumber,
        String kind,
        Map<String, String> placeholderValuesSnapshot,
        List<SignerConfigDto> signersConfigSnapshot,
        List<AppendixDto> appendixFileUuidsSnapshot,
        List<PdfArtifactRef> pdfArtifactsSnapshot,
        String signingCaseKey,
        String recipientEmail,
        String recipientName,
        String note,
        String createdByUserUuid,
        LocalDateTime createdAt
) {
    /**
     * One PDF artifact referenced by a revision — either a generated PDF from
     * the Word template or an appendix file that was bundled with the Send.
     */
    public record PdfArtifactRef(String filename, String fileUuid) {
    }
}
