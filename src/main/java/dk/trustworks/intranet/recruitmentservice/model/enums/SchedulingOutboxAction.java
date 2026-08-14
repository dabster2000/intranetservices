package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * The external writes the Method B orchestrator may enqueue (plan §8.3).
 * Persisted verbatim in {@code recruitment_scheduling_outbox.action};
 * each value maps to one registered {@code SchedulingOutboxExecutor}.
 * An action whose executor is not registered yet (later-phase actions
 * deployed dark) is left PENDING untouched — never failed.
 */
public enum SchedulingOutboxAction {
    /** Post the proposal Block Kit card as a DM to one interviewer
     * (payload: approval uuid). Phase 9. */
    SEND_PROPOSAL_DM,
    /** Create one attendee-less hold event (payload: hold uuid — also
     * the Graph transactionId). Phase 9. */
    CREATE_HOLD,
    /** Delete one hold's Graph event; 404 counts as done (payload:
     * hold uuid). Phase 9. */
    DELETE_HOLD,
    /** DM the recruiter a deterministic status/escalation notice
     * (payload: structural notice kind + refs — never free text). Phase 9. */
    SEND_RECRUITER_DM,
    /** Send the candidate the option mail through the mail outbox
     * (payload: batch uuid). Phase 11. */
    SEND_OPTION_MAIL,
    /** DM one interviewer about their availability evidence: the D6
     * summary card (payload kind SUMMARY + evidence uuid) or the
     * non-blocking stale-evidence notice at finalization (kind
     * RECONFIRM + slot times, spec §23). Phase 12. */
    SEND_EVIDENCE_DM,
    /** Close the loop with the interviewers after finalization
     * (spec §16.3): rewrite every proposal card — booked for the
     * winner, closed for the released siblings — and DM each required
     * interviewer the booked time (payload: selected slot uuid).
     * Phase 12 (folded-in §16.3 loose end). */
    NOTIFY_FINALIZED
}
