package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Highest completed (or ongoing) education level (spec §4.1). {@code OTHER}
 * pairs with the free-text {@code education_other} column.
 * <p>
 * Persisted as {@code VARCHAR(20)}; DB guard {@code chk_rc_education_enum}.
 */
public enum CandidateEducationLevel {
    STUDENT,
    BACHELOR,
    MASTER,
    PHD,
    OTHER
}
