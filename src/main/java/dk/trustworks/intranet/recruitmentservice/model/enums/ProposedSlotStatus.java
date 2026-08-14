package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle of one proposed slot (plan §8.1), persisted verbatim in
 * {@code recruitment_proposed_slot.status}.
 */
public enum ProposedSlotStatus {
    /** The planner picked it; not yet sent to interviewers. */
    DISCOVERED,
    /** Proposal DMs are out; every required approval is PENDING. */
    PROPOSED,
    /** Some, not all, required interviewers approved. */
    PARTIALLY_APPROVED,
    /** Every required interviewer approved; recheck+holds are next. */
    APPROVED,
    /** Availability recheck / hold creation in progress. */
    RECHECKING,
    /** All holds exist in the calendars — the slot is protected. */
    HELD,
    /** Part of the option batch sent to the candidate (Phase 11). */
    OFFERED,
    /** The candidate chose this slot; finalization running. */
    SELECTED,
    /** Terminal: became the real interview. */
    FINALIZED,
    /** Terminal: declined, conflicted, or compensated away —
     * {@code reject_reason} says why. */
    REJECTED,
    /** Terminal: dropped while still valid (loser of a selection,
     * recruiter release, request cancelled) — holds deleted. */
    RELEASED,
    /** Terminal: ran past its {@code expires_at}. */
    EXPIRED;

    /** True while the slot still counts toward the requested options. */
    public boolean isLive() {
        return !isTerminal();
    }

    public boolean isTerminal() {
        return this == FINALIZED || this == REJECTED
                || this == RELEASED || this == EXPIRED;
    }
}
