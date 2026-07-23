package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Interview lifecycle (ATS spec §4.1). {@code PLANNED} is reserved by the
 * spec enum but unused in P11 — the API always schedules with a time, so
 * rows are born {@code SCHEDULED}. {@code HELD} is set by the first
 * scorecard submission; {@code CANCELLED} is terminal.
 */
public enum RecruitmentInterviewStatus {
    PLANNED,
    SCHEDULED,
    HELD,
    CANCELLED
}
