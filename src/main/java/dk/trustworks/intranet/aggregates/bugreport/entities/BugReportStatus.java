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
    RESOLVED,
    CLOSED;

    /**
     * Returns the set of valid next states for this status.
     * Admin force-close (Any -> CLOSED) is handled separately in the entity.
     */
    public Set<BugReportStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> Set.of(SUBMITTED);
            case SUBMITTED -> Set.of(IN_PROGRESS);
            case IN_PROGRESS -> Set.of(RESOLVED, CLOSED);
            case RESOLVED -> Set.of(CLOSED);
            case CLOSED -> Set.of();
        };
    }
}
