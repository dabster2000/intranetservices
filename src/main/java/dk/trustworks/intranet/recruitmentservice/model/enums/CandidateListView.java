package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Saved-view vocabulary of the P8 candidate database grid — the
 * {@code view} parameter of {@code GET /recruitment/candidates} (spec §6.1:
 * Active pipeline / Talent pool / Silver medalists / All; "Consent
 * expiring" arrives with P19). Composable with every other list filter; an
 * unknown value answers 400.
 */
public enum CandidateListView {
    /** Candidates with at least one open (non-terminal) application. */
    ACTIVE_PIPELINE,
    /** Candidates whose status is {@code POOLED}. */
    TALENT_POOL,
    /** Pooled candidates in the {@code SILVER_MEDALIST} bucket. */
    SILVER_MEDALISTS,
    /** No view filter — the default. */
    ALL
}
