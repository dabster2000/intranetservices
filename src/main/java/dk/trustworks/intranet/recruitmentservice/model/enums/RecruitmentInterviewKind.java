package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Interview kind (ATS spec §4.1): a {@code ROUND} counts toward the stage
 * machine (round 1–3 ↔ stage {@code INTERVIEW_n}); {@code INFORMAL} is
 * Airtable's <em>uformel snak</em> — schedulable at any point before or
 * between rounds without advancing the stage, and no scorecard is allowed
 * (a plain note suffices, spec §5.3).
 */
public enum RecruitmentInterviewKind {
    INFORMAL,
    ROUND
}
