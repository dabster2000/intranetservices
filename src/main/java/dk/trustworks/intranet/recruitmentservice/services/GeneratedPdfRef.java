package dk.trustworks.intranet.recruitmentservice.services;

/**
 * Reference to a generated PDF persisted in S3 by a recruitment Send action.
 * Serialized as a JSON array entry into
 * {@code candidate_dossier_revisions.generated_pdfs_snapshot}.
 */
public record GeneratedPdfRef(String filename, String fileUuid) { }
