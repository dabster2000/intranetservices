package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;

/**
 * One row of the whole-corpus listing behind the HR document console
 * (spec §6.7).
 *
 * <p>Deliberately not {@link EmployeeDocumentDTO}: the console's three
 * views — review queue, duplicates, uncategorized — are all cross-employee,
 * so each row must name the employee whose file it is, which the
 * per-employee DTO has no reason to carry. It is otherwise narrower: no
 * signing linkage, no uploader, none of the fields only a single-document
 * view uses.</p>
 *
 * <p>{@code sha256} rides along because duplicate grouping happens in the
 * BFF, which is server-side. It is stripped there and never reaches the
 * browser — the console shows only whether a group is hash-proven.</p>
 */
public record EmployeeDocumentCorpusRowDTO(
        String uuid,
        String userUuid,
        /** "Firstname Lastname" of the employee whose file this is. */
        String userName,
        String filename,
        String displayName,
        String label,
        String category,
        String source,
        String contentType,
        long fileSizeBytes,
        String sha256,
        boolean hrOnly,
        boolean archived,
        boolean needsReview,
        String createdAt) {

    public static EmployeeDocumentCorpusRowDTO from(EmployeeDocument doc, String userName) {
        return new EmployeeDocumentCorpusRowDTO(
                doc.getUuid(),
                doc.getUserUuid(),
                userName,
                doc.getOriginalFilename(),
                doc.getDisplayName(),
                doc.getLabel(),
                doc.getCategory().name(),
                doc.getSource().name(),
                doc.getContentType(),
                doc.getFileSizeBytes(),
                doc.getSha256(),
                doc.isHrOnly(),
                doc.isArchived(),
                doc.isNeedsReview(),
                doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
    }
}
