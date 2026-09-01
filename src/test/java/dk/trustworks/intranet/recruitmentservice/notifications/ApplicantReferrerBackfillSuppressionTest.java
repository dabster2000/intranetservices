package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventFixtures;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one-off referrer backfill must never DM anybody: the claims it acts on
 * are months old, and telling a colleague now that a stranger once named them
 * would be noise at best.
 * <p>
 * That guarantee is STRUCTURAL rather than a flag — the backfill appends
 * {@code APPLICANT_REFERRER_BACKFILLED} and this reactor acts only on
 * {@code APPLICANT_REFERRER_CLAIMED} — and this test pins it, because the
 * cheapest way to break it later is a well-meaning "handle both types" edit.
 * <p>
 * DB-free by construction: the reactor is built with {@code new}, so every
 * injected collaborator is null. Reaching ANY of them throws. So "the
 * backfilled event does not throw" is a genuine proof that the method
 * returned before touching the feature flag, the candidate lookup or Slack —
 * and the CLAIMED case throwing is the control that proves the test would
 * actually notice if the guard disappeared.
 */
class ApplicantReferrerBackfillSuppressionTest {

    private final ApplicantReferrerNotificationReactor reactor =
            new ApplicantReferrerNotificationReactor();

    private static RecruitmentEvent eventOfType(RecruitmentEventType type) {
        return RecruitmentEventFixtures.detachedEvent(
                type, RecruitmentEventVisibility.NORMAL,
                "4131b868-ee68-47bc-81a8-78954f2cec61", null);
    }

    @Test
    void backfilledEventIsIgnoredBeforeAnyCollaboratorIsTouched() {
        assertDoesNotThrow(
                () -> reactor.handle(eventOfType(RecruitmentEventType.APPLICANT_REFERRER_BACKFILLED)),
                "a backfilled link must not reach the flag, the candidate or Slack");
    }

    @Test
    void controlCase_aClaimedEventDoesReachTheCollaborators() {
        // Without this the test above would still pass if handle() started
        // returning early for everything.
        assertThrows(Exception.class,
                () -> reactor.handle(eventOfType(RecruitmentEventType.APPLICANT_REFERRER_CLAIMED)),
                "a real claim must get past the type guard (and NPE on the null flag here)");
    }

    @Test
    void theTwoTypesAreDistinct() {
        assertNotEquals(RecruitmentEventType.APPLICANT_REFERRER_CLAIMED,
                RecruitmentEventType.APPLICANT_REFERRER_BACKFILLED,
                "collapsing these into one type would make the sweep notify");
    }

    @Test
    void ourOwnBookkeepingEventIsAlsoIgnored() {
        assertDoesNotThrow(
                () -> reactor.handle(eventOfType(RecruitmentEventType.APPLICANT_REFERRER_NOTIFIED)),
                "the reactor must not react to its own side-effect record");
    }
}
