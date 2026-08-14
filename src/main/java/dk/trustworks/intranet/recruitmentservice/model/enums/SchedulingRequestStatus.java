package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Method B scheduling request lifecycle (spec §15), persisted verbatim in
 * {@code recruitment_scheduling_request.status}. Legal transitions are
 * owned by {@code SchedulingStateMachine} — nothing mutates the status
 * without asking it first.
 */
public enum SchedulingRequestStatus {
    /** Created; the next advance sweep starts searching. */
    DRAFT,
    /** Looking for slots; no live proposals out yet. */
    SEARCHING,
    /** Proposals are with the interviewers, awaiting answers. */
    WAITING_FOR_INTERVIEWERS,
    /** At least one slot is held; still securing the requested count. */
    HOLDING_OPTIONS,
    /** The requested count is secured — awaiting the D11 recruiter review
     * (or, with review off, the Phase 11 auto-send). */
    READY_FOR_CANDIDATE,
    /** Options are with the candidate (Phase 11 batch sent). */
    WAITING_FOR_CANDIDATE,
    /** A selection landed; the finalization saga is running. */
    FINALIZING,
    /** Terminal: the real interview exists, all holds released. */
    SCHEDULED,
    /** Terminal: automation gave up (deadline, exhaustion, or the
     * recruiter took over) — reason on {@code handback_reason}. */
    HANDED_BACK,
    /** Terminal: the candidate deadline passed unanswered. */
    EXPIRED,
    /** Terminal: cancelled by the recruiter. */
    CANCELLED;

    /** True when no further automation will ever touch the request. */
    public boolean isTerminal() {
        return this == SCHEDULED || this == HANDED_BACK
                || this == EXPIRED || this == CANCELLED;
    }
}
