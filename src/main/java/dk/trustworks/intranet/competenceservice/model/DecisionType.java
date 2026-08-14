package dk.trustworks.intranet.competenceservice.model;

/**
 * An entry in the append-only approval ledger.
 *
 * <p>REVOKED exists because an immutable attempt row cannot be corrected any other way:
 * without it, a wrongly approved result could only be fixed by editing evidence, which
 * the database refuses. The effective state of an attempt is its latest decision, or
 * PENDING when there is none.
 */
public enum DecisionType {
    APPROVED,
    REVOKED
}
