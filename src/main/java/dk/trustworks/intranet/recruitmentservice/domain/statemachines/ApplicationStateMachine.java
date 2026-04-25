package dk.trustworks.intranet.recruitmentservice.domain.statemachines;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ApplicationStateMachine {

    private static final Set<ApplicationStage> SIDE_EXITS_FROM_ANY_ACTIVE =
            EnumSet.of(ApplicationStage.REJECTED, ApplicationStage.WITHDRAWN);

    private static final Map<ApplicationStage, Set<ApplicationStage>> CONSULTANT;
    private static final Map<ApplicationStage, Set<ApplicationStage>> OTHER;

    static {
        CONSULTANT = buildConsultant();
        OTHER = buildOther();
    }

    private ApplicationStateMachine() {}

    public static Set<ApplicationStage> allowedTransitions(ApplicationStage from, PipelineKind kind) {
        Map<ApplicationStage, Set<ApplicationStage>> table = (kind == PipelineKind.OTHER) ? OTHER : CONSULTANT;
        return table.getOrDefault(from, Set.of());
    }

    public static void assertTransitionAllowed(ApplicationStage from, ApplicationStage to, PipelineKind kind) {
        Set<ApplicationStage> allowed = allowedTransitions(from, kind);
        if (!allowed.contains(to)) {
            throw new InvalidTransitionException(
                    "Cannot transition Application from " + from + " to " + to + " (pipeline=" + kind + ")",
                    allowed.stream().map(Enum::name).toList());
        }
    }

    private static Map<ApplicationStage, Set<ApplicationStage>> buildConsultant() {
        Map<ApplicationStage, Set<ApplicationStage>> m = new EnumMap<>(ApplicationStage.class);
        m.put(ApplicationStage.SOURCED, plus(EnumSet.of(ApplicationStage.CONTACTED), SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.CONTACTED, plus(
                EnumSet.of(ApplicationStage.SCREENING, ApplicationStage.TALENT_POOL),
                SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.SCREENING, plus(
                EnumSet.of(ApplicationStage.FIRST_INTERVIEW, ApplicationStage.TALENT_POOL),
                SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.FIRST_INTERVIEW, plus(
                EnumSet.of(ApplicationStage.CASE_OR_TECH_INTERVIEW, ApplicationStage.FINAL_INTERVIEW),
                SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.CASE_OR_TECH_INTERVIEW, plus(
                EnumSet.of(ApplicationStage.FINAL_INTERVIEW), SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.FINAL_INTERVIEW, plus(
                EnumSet.of(ApplicationStage.OFFER), SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.OFFER, plus(
                EnumSet.of(ApplicationStage.ACCEPTED), SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.ACCEPTED, plus(
                EnumSet.of(ApplicationStage.CONVERTED), SIDE_EXITS_FROM_ANY_ACTIVE));
        m.put(ApplicationStage.CONVERTED, EnumSet.noneOf(ApplicationStage.class));
        m.put(ApplicationStage.REJECTED, EnumSet.noneOf(ApplicationStage.class));
        m.put(ApplicationStage.WITHDRAWN, EnumSet.noneOf(ApplicationStage.class));
        m.put(ApplicationStage.TALENT_POOL, EnumSet.noneOf(ApplicationStage.class));
        return Map.copyOf(m);
    }

    private static Map<ApplicationStage, Set<ApplicationStage>> buildOther() {
        Map<ApplicationStage, Set<ApplicationStage>> m = new EnumMap<>(buildConsultant());
        Set<ApplicationStage> screening = EnumSet.copyOf(m.get(ApplicationStage.SCREENING));
        screening.add(ApplicationStage.OFFER);
        m.put(ApplicationStage.SCREENING, Set.copyOf(screening));
        return Map.copyOf(m);
    }

    private static Set<ApplicationStage> plus(Set<ApplicationStage> primary, Set<ApplicationStage> sides) {
        Set<ApplicationStage> all = EnumSet.copyOf(primary);
        all.addAll(sides);
        return Set.copyOf(all);
    }
}
