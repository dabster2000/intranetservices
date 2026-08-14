package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.CANCELLED;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.DRAFT;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.EXPIRED;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.FINALIZING;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.HANDED_BACK;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.HOLDING_OPTIONS;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.READY_FOR_CANDIDATE;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.SCHEDULED;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.SEARCHING;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.WAITING_FOR_CANDIDATE;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.WAITING_FOR_INTERVIEWERS;

/**
 * The Method B request state machine (spec §15, plan §8.2) — the single
 * authority on which {@link SchedulingRequestStatus} transitions exist.
 * Every status write goes through {@link #require}, so an impossible
 * move (a lost race, a stale Slack claim, a buggy sweep step) fails loud
 * instead of corrupting the workflow.
 * <p>
 * Pure and CDI-free so the exhaustive transition-matrix test lives in
 * the DB-free tier that gates deploys.
 */
public final class SchedulingStateMachine {

    private static final Map<SchedulingRequestStatus, Set<SchedulingRequestStatus>> TRANSITIONS =
            new EnumMap<>(SchedulingRequestStatus.class);

    static {
        // Forward path. CANCELLED is reachable from every non-terminal
        // state (the recruiter can always stop automation); HANDED_BACK
        // likewise (deadline, exhaustion, manual takeover).
        TRANSITIONS.put(DRAFT,
                EnumSet.of(SEARCHING, HANDED_BACK, CANCELLED));
        TRANSITIONS.put(SEARCHING,
                EnumSet.of(WAITING_FOR_INTERVIEWERS, HANDED_BACK, CANCELLED));
        // Back to SEARCHING when every live proposal died (declines,
        // conflicts) and the pipeline is empty again.
        TRANSITIONS.put(WAITING_FOR_INTERVIEWERS,
                EnumSet.of(SEARCHING, HOLDING_OPTIONS, READY_FOR_CANDIDATE,
                        HANDED_BACK, CANCELLED));
        TRANSITIONS.put(HOLDING_OPTIONS,
                EnumSet.of(READY_FOR_CANDIDATE, WAITING_FOR_INTERVIEWERS, SEARCHING,
                        HANDED_BACK, CANCELLED));
        // Losing a hold (reconciliation MISSING, recruiter release) can
        // drop the secured count below the requested count again.
        TRANSITIONS.put(READY_FOR_CANDIDATE,
                EnumSet.of(WAITING_FOR_CANDIDATE, HOLDING_OPTIONS,
                        WAITING_FOR_INTERVIEWERS, SEARCHING, HANDED_BACK, CANCELLED));
        TRANSITIONS.put(WAITING_FOR_CANDIDATE,
                EnumSet.of(FINALIZING, EXPIRED, HANDED_BACK, CANCELLED));
        // Recheck failure at finalization: remaining valid options → the
        // candidate chooses again; none left → re-search or hand back
        // (plan §11.3).
        TRANSITIONS.put(FINALIZING,
                EnumSet.of(SCHEDULED, WAITING_FOR_CANDIDATE, SEARCHING,
                        HANDED_BACK, CANCELLED));
        TRANSITIONS.put(SCHEDULED, EnumSet.noneOf(SchedulingRequestStatus.class));
        TRANSITIONS.put(HANDED_BACK, EnumSet.noneOf(SchedulingRequestStatus.class));
        TRANSITIONS.put(EXPIRED, EnumSet.noneOf(SchedulingRequestStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(SchedulingRequestStatus.class));
    }

    private SchedulingStateMachine() {
    }

    /** True when {@code from → to} is a legal transition. */
    public static boolean canTransition(SchedulingRequestStatus from,
                                        SchedulingRequestStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** The legal targets from {@code from} (empty for terminals). */
    public static Set<SchedulingRequestStatus> allowedTargets(SchedulingRequestStatus from) {
        return Set.copyOf(TRANSITIONS.getOrDefault(from, Set.of()));
    }

    /**
     * Assert {@code from → to}; throws {@link IllegalStateException}
     * otherwise. Callers set the status only after this returns.
     */
    public static void require(SchedulingRequestStatus from, SchedulingRequestStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Illegal scheduling-request transition " + from + " -> " + to);
        }
    }
}
