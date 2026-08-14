package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Where one availability-evidence row came from (plan §12.1, spec §8.4).
 * Persisted verbatim in {@code recruitment_availability_evidence.source_type}.
 */
public enum EvidenceSourceType {
    /** Reserved: evidence derived from an explicit approve/decline button. */
    BUTTON,
    /** Slack free text (Danish/English) run through the extraction. */
    TEXT,
    /** A calendar screenshot/photo run through the vision extraction (Phase 13). */
    IMAGE,
    /** Entered by the recruiter on the request panel (the Phase 14 manual-review flow). */
    RECRUITER,
    /** A resubmission after the interviewer pressed Ret on a summary card. */
    CORRECTION
}
