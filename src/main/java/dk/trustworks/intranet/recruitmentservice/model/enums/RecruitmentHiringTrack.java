package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * The three hiring tracks a position can follow (ATS spec §4.1).
 * <p>
 * The track drives defaults (stage set, scorecard) and validation:
 * {@link #PRACTICE_TEAM} requires a practice, {@link #STAFF_ROLE} requires a
 * named hiring owner, and {@link #PARTNER} positions are confidential —
 * visible only to their circle members. The track is immutable after
 * creation (changing it would invalidate the stage set and circle
 * semantics).
 */
public enum RecruitmentHiringTrack {

    /** Consultant hire into a practice; the concrete team may be decided at offer. */
    PRACTICE_TEAM,

    /** Partner-level hire; circle-gated confidentiality, extra interview round by default. */
    PARTNER,

    /** Individual staff role (e.g. finance, marketing) with a named hiring owner. */
    STAFF_ROLE
}
