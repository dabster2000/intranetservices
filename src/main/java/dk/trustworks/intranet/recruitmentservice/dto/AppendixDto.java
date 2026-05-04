package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Wire representation of one appendix attached to a dossier — the metadata
 * needed to render the dossier's appendix list and to drive download links.
 * The actual file bytes live in S3 at {@link #fileUuid}.
 *
 * @param uuid             {@code candidate_dossier_appendices.uuid}
 * @param fileUuid         S3 storage key
 * @param originalFilename sanitised filename presented to the recipient
 * @param displayOrder     1-based ordering within the dossier
 */
public record AppendixDto(
        String uuid,
        String fileUuid,
        String originalFilename,
        int displayOrder
) {
}
