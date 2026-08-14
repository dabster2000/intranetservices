package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * The D9 lifecycle of one availability-evidence row (plan §12.4). Only
 * CONFIRMED evidence ever reaches the slot planner; everything else is
 * audit surface.
 */
public enum EvidenceConfirmationStatus {
    /** Interpreted, summary sent, waiting for Bekræft/Ret. */
    PENDING,
    /** The interviewer confirmed (or the statement was unambiguous —
     * {@code requiresConfirmation=false} auto-confirms, plan §12.4). */
    CONFIRMED,
    /** The interviewer pressed Ret (or cancelled the submission). */
    CANCELLED,
    /** Replaced by newer confirmed evidence from the same interviewer
     * with an overlapping covered range (spec §12.3). */
    SUPERSEDED,
    /** Past its covered period (spec §23), or PENDING for 48 h without
     * an answer — ignored by the engine either way. */
    EXPIRED,
    /** UNKNOWN intent or failed backend validation — never had
     * constraints in play; listed for Phase 14 manual review. */
    REJECTED;

    /** The one status the planner consumes. */
    public boolean isSchedulingInput() {
        return this == CONFIRMED;
    }
}
