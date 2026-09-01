package dk.trustworks.intranet.agreementservice.model.enums;

/**
 * Review state of one backfill document (template-clauses spec §4.8/§10).
 *
 * <p>The spec's four review states plus two terminal extraction
 * outcomes: {@code NO_PROPOSALS} (the model found no negotiated terms —
 * most documents: CVs, certificates, standard letters) and
 * {@code FAILED} (unreadable PDF / extraction error — retried by the
 * next run). Only {@code PROPOSED} items are reviewable; confirm and
 * reject are row-locked one-shots.</p>
 */
public enum BackfillItemStatus {
    PROPOSED,
    CONFIRMED,
    EDITED,
    REJECTED,
    NO_PROPOSALS,
    FAILED
}
