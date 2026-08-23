package dk.trustworks.intranet.recruitmentservice.events;

/**
 * Test-only factory for detached {@link RecruitmentEvent} rows. The entity is
 * deliberately setter-less (its only writer is
 * {@code RecruitmentEventRecorder}, package-private field assignment), so
 * tests outside this package cannot construct one — this factory is the
 * sanctioned hole, sized exactly for predicate tests: type, visibility and
 * the three soft FKs, nothing persisted.
 */
public final class RecruitmentEventFixtures {

    private RecruitmentEventFixtures() {
    }

    public static RecruitmentEvent detachedEvent(RecruitmentEventType type,
                                                 RecruitmentEventVisibility visibility,
                                                 String candidateUuid,
                                                 String positionUuid) {
        RecruitmentEvent event = new RecruitmentEvent();
        event.eventType = type;
        event.visibility = visibility;
        event.candidateUuid = candidateUuid;
        event.positionUuid = positionUuid;
        return event;
    }
}
