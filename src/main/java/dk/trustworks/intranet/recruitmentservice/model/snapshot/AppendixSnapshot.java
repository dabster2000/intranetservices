package dk.trustworks.intranet.recruitmentservice.model.snapshot;

import java.util.Objects;

/**
 * Immutable, frozen-at-Send-time reference to a single appendix that was
 * attached to the dossier when a revision was allocated.
 * <p>
 * The full file metadata (size, content type) lives on the
 * {@code candidate_dossier_appendices} row; this snapshot captures only the
 * minimum needed to reconstruct what the recipient actually saw — the S3
 * file UUID, the original filename presented to them, and the display order
 * within the dossier.
 *
 * @param fileUuid          S3 storage key (mirrors {@code file_uuid})
 * @param originalFilename  sanitised filename that was attached
 * @param displayOrder      1-based ordering within the dossier
 */
public record AppendixSnapshot(
        String fileUuid,
        String originalFilename,
        int displayOrder
) {
    public AppendixSnapshot {
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        if (displayOrder < 1) {
            throw new IllegalArgumentException("displayOrder must be >= 1");
        }
    }
}
