package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Saved-view vocabulary of the P8 candidate database grid — the
 * {@code view} parameter of {@code GET /recruitment/candidates} (spec §6.1:
 * Active pipeline / Talent pool / Silver medalists / Consent expiring /
 * All). Composable with every other list filter; an unknown value
 * answers 400.
 */
public enum CandidateListView {
    /** Candidates with at least one open (non-terminal) application. */
    ACTIVE_PIPELINE,
    /** Candidates whose status is {@code POOLED}. */
    TALENT_POOL,
    /** Pooled candidates in the {@code SILVER_MEDALIST} bucket. */
    SILVER_MEDALISTS,
    /**
     * Pooled candidates whose retention deadline falls inside the renewal
     * window ({@code recruitment.gdpr.renewal-first-days}, default 30) —
     * the P19 "Consent expiring" saved view: people about to be deleted
     * unless they (re-)grant pool consent.
     */
    CONSENT_EXPIRING,
    /** No view filter — the default. */
    ALL
}
