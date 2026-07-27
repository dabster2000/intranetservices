package dk.trustworks.intranet.documentservice.dto;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;

/**
 * Wire shape for an employee document (spec §6.4 — list DTO, no bytes).
 * camelCase field names are the contract with the BFF routes.
 * {@code uploadedByName} is resolved by the resource layer for display
 * (null for system writers).
 */
public record EmployeeDocumentDTO(
        String uuid,
        String userUuid,
        String filename,
        String label,
        String category,
        String source,
        String contentType,
        long fileSizeBytes,
        String sha256,
        String signingCaseKey,
        Integer documentIndex,
        boolean hrOnly,
        boolean archived,
        boolean needsReview,
        String uploadedBy,
        String uploadedByName,
        String createdAt,
        String updatedAt) {

    public static EmployeeDocumentDTO from(EmployeeDocument doc, String uploadedByName) {
        return new EmployeeDocumentDTO(
                doc.getUuid(),
                doc.getUserUuid(),
                doc.getOriginalFilename(),
                doc.getLabel(),
                doc.getCategory().name(),
                doc.getSource().name(),
                doc.getContentType(),
                doc.getFileSizeBytes(),
                doc.getSha256(),
                doc.getSigningCaseKey(),
                doc.getDocumentIndex(),
                doc.isHrOnly(),
                doc.isArchived(),
                doc.isNeedsReview(),
                doc.getUploadedBy(),
                uploadedByName,
                doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString(),
                doc.getUpdatedAt() == null ? null : doc.getUpdatedAt().toString());
    }
}
