package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Lifecycle of a library clause. Only {@link #ACTIVE} clauses are offered
 * to preparers; {@link #RETIRED} keeps history intact (sent documents and
 * {@code signing_case_clauses} rows keep referencing the clause).
 */
public enum ClauseStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
