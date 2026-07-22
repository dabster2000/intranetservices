package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * A circle member's role on a partner-track position (ATS spec §4.1).
 * <p>
 * Membership itself grants visibility; {@link #OWNER} and {@link #RECRUITER}
 * may additionally manage the circle (add/remove members). The creator of a
 * partner-track position is auto-added as {@link #OWNER}.
 */
public enum RecruitmentCircleRole {
    OWNER,
    RECRUITER,
    PARTICIPANT
}
