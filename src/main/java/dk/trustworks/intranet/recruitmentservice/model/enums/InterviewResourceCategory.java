package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * What kind of shared interview material an {@code interview_resources}
 * row is. Persisted as {@code VARCHAR(30)}; DB guard
 * {@code chk_ires_category_enum} (V480) — adding a value here requires
 * widening that CHECK.
 */
public enum InterviewResourceCategory {
    /** How to run an interview: structure, question banks, evaluation guidance. */
    INTERVIEW_GUIDE,
    /** Case exercises handed to candidates ("CASE" clashes with SQL keyword). */
    CASE_MATERIAL,
    /** Scorecards / assessment sheets interviewers fill in. */
    ASSESSMENT_TEMPLATE,
    /** Anything else worth sharing with interviewers. */
    OTHER
}
