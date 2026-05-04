package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Kind of action that produced a {@code CandidateDossierRevision} snapshot.
 * <ul>
 *   <li>{@link #REVIEW_EMAIL} — dossier sent by email for review (no PDF link).</li>
 *   <li>{@link #REVIEW_PDF}   — dossier sent for review with a generated PDF.</li>
 *   <li>{@link #SIGNATURE}    — dossier sent for binding signature via NextSign.</li>
 * </ul>
 * <p>
 * Persisted as {@code VARCHAR(20)} via {@code @Enumerated(EnumType.STRING)};
 * the database also enforces the value set with {@code chk_cdr_kind_enum}.
 */
public enum RevisionKind {
    REVIEW_EMAIL,
    REVIEW_PDF,
    SIGNATURE
}
