package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Coded rejection reasons (spec §4.1: "coded for reporting"). Mandatory on
 * every {@code REJECTED} terminal — the P20 reports aggregate on these
 * codes, so free text alone is never enough. Free-text elaboration goes in
 * the {@code APPLICATION_REJECTED} event's {@code pii} block.
 * <p>
 * The spec left the concrete code list open; this starter set covers the
 * reasons observed in the Airtable history (recorded in findings §P4).
 * Append new codes as needed — never rename one that has been persisted.
 * <p>
 * Persisted as {@code VARCHAR(40)} on {@code recruitment_applications}.
 */
public enum RecruitmentRejectionReason {
    /** The profile does not match what the role needs. */
    PROFILE_MISMATCH,
    /** Right profile, wrong seniority (too junior / too senior). */
    EXPERIENCE_LEVEL,
    /** Concerns about fit with the Trustworks DNA / consulting life. */
    CULTURE_FIT,
    /** Compensation expectations too far apart. */
    SALARY_EXPECTATIONS,
    /** The opening was filled by another candidate. */
    POSITION_FILLED,
    /** Good candidate, wrong moment (hiring paused, timing mismatch). */
    TIMING,
    /** Anything else — elaborate in the note (stored in the event pii). */
    OTHER
}
