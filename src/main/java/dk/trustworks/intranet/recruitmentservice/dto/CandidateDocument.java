package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;

/**
 * One row of the P8 profile's Documents tab ({@code ICandidateDocument} in
 * the FE↔BE contract): a {@code files} row related to the candidate,
 * enriched from its {@code DOCUMENT_UPLOADED} event (joined on
 * {@code payload.file_uuid}) and from the flow tables (dossier snapshots,
 * appendices, onboarding submissions — {@code CandidateDocumentClassifier}).
 * Kind resolution: newest {@code DOCUMENT_KIND_CHANGED} &gt; the upload
 * event's kind &gt; flow-derived &gt; {@code OTHER}.
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
        /**
         * {@code CV} / {@code COVER_LETTER} / {@code CONTRACT_DRAFT} /
         * {@code SIGNED_DOCUMENT} / {@code APPENDIX} / {@code ID_DOCUMENT} /
         * {@code OTHER}.
         */
        String kind,
        /** e.g. {@code public_form}; null when no event matches. */
        String origin,
        /** {@code payload.reason}; null unless a duplicate submission. */
        String duplicateReason,
        /**
         * True when the system could not classify this file itself (no
         * specific upload-event kind, no flow derivation) — only such
         * files may be manually re-typed; a manual re-type keeps them
         * editable so mistakes stay correctable.
         */
        boolean kindEditable
) {
}
