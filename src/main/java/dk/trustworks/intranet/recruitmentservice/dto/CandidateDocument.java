package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;

/**
 * One row of the P8 profile's Documents tab ({@code ICandidateDocument} in
 * the FE↔BE contract): a {@code files} row related to the candidate,
 * enriched from its {@code DOCUMENT_UPLOADED} event (joined on
 * {@code payload.file_uuid}). Files without a matching event (dossier PDFs,
 * identity documents, pre-P5 uploads) render as kind {@code OTHER}.
 * {@code duplicateReason} carries {@code payload.reason}
 * ({@code DUPLICATE_PUBLIC_SUBMISSION}) — the frontend renders it
 * prominently: it is the only trace of repeat contact (findings §P5).
 */
public record CandidateDocument(
        String fileUuid,
        /** The stored (sanitized) filename, e.g. {@code cv.pdf}. */
        String filename,
        /** From the event payload; null when no event matches. */
        String contentType,
        /** From the event payload; null when no event matches. */
        Long sizeBytes,
        LocalDateTime uploadedAt,
        /** {@code CV} / {@code COVER_LETTER} / {@code OTHER}. */
        String kind,
        /** e.g. {@code public_form}; null when no event matches. */
        String origin,
        /** {@code payload.reason}; null unless a duplicate submission. */
        String duplicateReason
) {
}
