package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle status of a recruitment position (ATS spec §4.1).
 * <p>
 * {@link #CLOSED} is terminal and only reachable through the dedicated close
 * endpoint (which appends {@code POSITION_CLOSED}); a plain update may toggle
 * {@link #OPEN} ↔ {@link #ON_HOLD} but never close or reopen.
 */
public enum RecruitmentPositionStatus {
    OPEN,
    ON_HOLD,
    CLOSED
}
