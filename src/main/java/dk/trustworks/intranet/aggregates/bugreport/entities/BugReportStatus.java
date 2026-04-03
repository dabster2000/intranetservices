package dk.trustworks.intranet.aggregates.bugreport.entities;

import java.util.Set;

/**
 * Bug report lifecycle states with a defined state machine.
 * Transition rules are enforced by {@link BugReport#canTransitionTo(BugReportStatus)}.
 */
public enum BugReportStatus {
    DRAFT,
    SUBMITTED,
    IN_PROGRESS,
    AUTO_FIX_REQUESTED,
    RESOLVED,
    CLOSED,
    REJECTED;

    /**
     * Returns the set of valid next states for this status.
     * Admin force-close (Any -> CLOSED) is handled separately in the entity.
     *
     * <p>AUTO_FIX_REQUESTED transitions:
     * <ul>
     *   <li>SUBMITTED/IN_PROGRESS -> AUTO_FIX_REQUESTED: admin clicks "Request Auto-Fix"</li>
     *   <li>AUTO_FIX_REQUESTED -> IN_PROGRESS: worker completes with PR</li>
     *   <li>AUTO_FIX_REQUESTED -> SUBMITTED: worker fails, reverts to previous status</li>
     *   <li>AUTO_FIX_REQUESTED -> CLOSED: admin force-close</li>
     *   <li>Any non-terminal -> REJECTED: admin force-reject (handled in entity)</li>
     * </ul>
     */
    public Set<BugReportStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> Set.of(SUBMITTED);
            case SUBMITTED -> Set.of(IN_PROGRESS, AUTO_FIX_REQUESTED, REJECTED, CLOSED);
            case IN_PROGRESS -> Set.of(AUTO_FIX_REQUESTED, RESOLVED, REJECTED, CLOSED);
            case AUTO_FIX_REQUESTED -> Set.of(IN_PROGRESS, SUBMITTED, REJECTED, CLOSED);
            case RESOLVED -> Set.of(CLOSED);
            case CLOSED -> Set.of();
            case REJECTED -> Set.of();
        };
    }
}
