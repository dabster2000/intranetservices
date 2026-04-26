package dk.trustworks.intranet.recruitmentservice.domain.statemachines;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Interview state machine.
 *
 * <p>Allowed transitions:
 * <ul>
 *   <li>SCHEDULED   → HELD, CANCELLED</li>
 *   <li>HELD        → ROUNDED_UP, CANCELLED</li>
 *   <li>CANCELLED   → SCHEDULED (reschedule)</li>
 *   <li>ROUNDED_UP  → (terminal)</li>
 *   <li>RESCHEDULED → (reserved alias; unused for outgoing transitions)</li>
 * </ul>
 */
@ApplicationScoped
public class InterviewStateMachine {

    private static final Map<InterviewStatus, Set<InterviewStatus>> LEGAL =
        new EnumMap<>(InterviewStatus.class);

    static {
        LEGAL.put(InterviewStatus.SCHEDULED,   EnumSet.of(InterviewStatus.HELD, InterviewStatus.CANCELLED));
        LEGAL.put(InterviewStatus.HELD,        EnumSet.of(InterviewStatus.ROUNDED_UP, InterviewStatus.CANCELLED));
        LEGAL.put(InterviewStatus.CANCELLED,   EnumSet.of(InterviewStatus.SCHEDULED));   // reschedule
        LEGAL.put(InterviewStatus.ROUNDED_UP,  EnumSet.noneOf(InterviewStatus.class));   // terminal
        LEGAL.put(InterviewStatus.RESCHEDULED, EnumSet.noneOf(InterviewStatus.class));   // unused alias; reserved
    }

    public boolean isLegalTransition(InterviewStatus from, InterviewStatus to) {
        return LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    public Set<InterviewStatus> allowedTransitions(InterviewStatus from) {
        return Set.copyOf(LEGAL.getOrDefault(from, Set.of()));
    }
}
