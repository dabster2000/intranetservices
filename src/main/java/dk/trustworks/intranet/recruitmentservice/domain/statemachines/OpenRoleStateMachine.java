package dk.trustworks.intranet.recruitmentservice.domain.statemachines;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenRoleStateMachine {

    private static final Map<RoleStatus, Set<RoleStatus>> ALLOWED;

    static {
        Map<RoleStatus, Set<RoleStatus>> m = new EnumMap<>(RoleStatus.class);
        m.put(RoleStatus.DRAFT,        Set.of(RoleStatus.SOURCING, RoleStatus.CANCELLED));
        m.put(RoleStatus.SOURCING,     Set.of(RoleStatus.PAUSED, RoleStatus.CANCELLED));
        m.put(RoleStatus.INTERVIEWING, Set.of(RoleStatus.PAUSED, RoleStatus.CANCELLED));
        m.put(RoleStatus.OFFER,        Set.of(RoleStatus.PAUSED, RoleStatus.CANCELLED));
        m.put(RoleStatus.PAUSED,       Set.of(RoleStatus.SOURCING, RoleStatus.CANCELLED));
        m.put(RoleStatus.FILLED,       Set.of());
        m.put(RoleStatus.CANCELLED,    Set.of());
        ALLOWED = Map.copyOf(m);
    }

    private OpenRoleStateMachine() {}

    public static Set<RoleStatus> allowedTransitions(RoleStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static void assertTransitionAllowed(RoleStatus from, RoleStatus to) {
        Set<RoleStatus> allowed = allowedTransitions(from);
        if (!allowed.contains(to)) {
            throw new InvalidTransitionException(
                    "Cannot transition OpenRole from " + from + " to " + to,
                    allowed.stream().map(Enum::name).toList());
        }
    }

    /**
     * Slice 1 only uses (false,false,false) - derived to SOURCING.
     * Later slices pass (hasOffer, hasInterview, hasAcceptedOffer) from child state.
     */
    public static RoleStatus deriveResumedStatus(boolean hasOffer, boolean hasInterview, boolean hasAcceptedOffer) {
        if (hasAcceptedOffer) return RoleStatus.OFFER; // FILLED transition is explicit, not on resume
        if (hasOffer) return RoleStatus.OFFER;
        if (hasInterview) return RoleStatus.INTERVIEWING;
        return RoleStatus.SOURCING;
    }
}
