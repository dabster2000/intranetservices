package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import dk.trustworks.intranet.recruitmentservice.slack.SlackAppHomeService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P23 §DoD — the targeted App Home refresh against the real chassis
 * (raw-inserted committed events + deterministic {@code catchUp()} sweeps)
 * with the publish service mocked: an event refreshes exactly the users it
 * directly concerns (submitter + owner, assigned interviewers, nudged
 * users, the referrer), gates off ⇒ silent offset advance (no backfill),
 * and the per-user debounce absorbs bursts (no publish storm).
 */
@QuarkusTest
class AppHomeRefreshReactorTest {

    @Inject
    EntityManager em;

    @Inject
    AppHomeRefreshReactor reactor;

    @InjectMock
    SlackAppHomeService appHomeService;

    private String actorUuid;
    private String ownerUuid;
    private String interviewerUuid;
    private String referrerUuid;
    private String practiceUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String interviewUuid;
    private String referralUuid;

    @BeforeEach
    void seed() {
        actorUuid = UUID.randomUUID().toString();
        ownerUuid = UUID.randomUUID().toString();
        interviewerUuid = UUID.randomUUID().toString();
        referrerUuid = UUID.randomUUID().toString();
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        interviewUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, actorUuid, "Alma", "Actor");
            P8ProfileFixtures.insertUser(em, ownerUuid, "Otto", "Owner");
            P8ProfileFixtures.insertUser(em, interviewerUuid, "Ivan", "Interviewer");
            P8ProfileFixtures.insertUser(em, referrerUuid, "Rene", "Referrer");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            // hiring owner set → the P17 owner ladder resolves to ownerUuid.
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, ownerUuid);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Cara", "Candidate", "ACTIVE", null, null, actorUuid);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");
            P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid,
                    "ROUND", 1, "[\"" + interviewerUuid + "\"]", "SCHEDULED");
            referralUuid = P12NotificationFixtures.insertReferral(em, referrerUuid,
                    "Reffi Referred", "knows the domain", "SUBMITTED", null);
        });
        // Drain any backlog while the gates are off (Mockito's default
        // enabled() = false), so each test's sweep only sees its own events.
        reactor.catchUp();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P12NotificationFixtures.deleteReferralsBy(em, referrerUuid);
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(actorUuid, ownerUuid, interviewerUuid, referrerUuid), practiceUuid);
        });
        reactor.catchUp();
    }

    // ---- Gates ------------------------------------------------------------------

    @Test
    void gatesOff_noPublish_offsetStillAdvances() {
        long seq = insertScorecardSubmitted();

        reactor.catchUp();

        verify(appHomeService, never()).publishFor(anyString());
        assertTrue(reactor.watermark() >= seq,
                "gated events must advance the offset (no backfill on later enable)");
    }

    // ---- Affected-user computation ----------------------------------------------

    @Test
    void scorecardSubmitted_refreshesSubmitterAndDecisionOwner() {
        when(appHomeService.enabled()).thenReturn(true);
        insertScorecardSubmitted();

        reactor.catchUp();

        verify(appHomeService).publishFor(actorUuid);
        verify(appHomeService).publishFor(ownerUuid);
        verify(appHomeService, never()).publishFor(interviewerUuid);
    }

    @Test
    void interviewScheduled_refreshesAssignedInterviewers() {
        when(appHomeService.enabled()).thenReturn(true);
        insertEvent("INTERVIEW_SCHEDULED",
                "{\"interview_uuid\":\"" + interviewUuid + "\",\"round\":1}");

        reactor.catchUp();

        verify(appHomeService).publishFor(interviewerUuid);
        verify(appHomeService, never()).publishFor(ownerUuid);
    }

    @Test
    void slaNudge_refreshesTheNudgedUsers() {
        when(appHomeService.enabled()).thenReturn(true);
        insertEvent("CANDIDATE_IDLE_NUDGED",
                "{\"application_uuid\":\"" + applicationUuid + "\",\"days_idle\":9,"
                        + "\"nudged_user_uuids\":[\"" + ownerUuid + "\"]}");

        reactor.catchUp();

        verify(appHomeService).publishFor(ownerUuid);
        verify(appHomeService, never()).publishFor(actorUuid);
    }

    @Test
    void referralSubmitted_refreshesTheReferrer() {
        when(appHomeService.enabled()).thenReturn(true);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "REFERRAL_SUBMITTED", null, null, null,
                        "USER", referrerUuid, "NORMAL",
                        "{\"referral_uuid\":\"" + referralUuid + "\",\"origin\":\"web\"}", null));

        reactor.catchUp();

        verify(appHomeService).publishFor(referrerUuid);
    }

    @Test
    void stageChange_refreshesTheReferrerOfAReferredCandidate() {
        when(appHomeService.enabled()).thenReturn(true);
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setCandidateReferredBy(em, candidateUuid, referrerUuid));
        insertEvent("APPLICATION_STAGE_CHANGED",
                "{\"from\":\"SCREENING\",\"to\":\"INTERVIEW_1\",\"direction\":\"FORWARD\"}");

        reactor.catchUp();

        verify(appHomeService).publishFor(referrerUuid);
    }

    // ---- Debounce ----------------------------------------------------------------

    @Test
    void burstOfEvents_debouncesToOnePublishPerUser() {
        when(appHomeService.enabled()).thenReturn(true);
        // Two scorecard nudges to the same interviewer in one sweep window.
        for (int i = 0; i < 2; i++) {
            insertEvent("SCORECARD_NUDGED",
                    "{\"interview_uuid\":\"" + interviewUuid + "\",\"nudge_number\":" + (i + 1)
                            + ",\"nudged_user_uuid\":\"" + interviewerUuid + "\"}");
        }

        reactor.catchUp();

        verify(appHomeService, times(1)).publishFor(interviewerUuid);
    }

    // ---- Helpers ----------------------------------------------------------------

    private long insertScorecardSubmitted() {
        return insertEvent("SCORECARD_SUBMITTED",
                "{\"interview_uuid\":\"" + interviewUuid + "\",\"round\":1,"
                        + "\"recommendation\":\"YES\",\"origin\":\"web\"}");
    }

    private long insertEvent(String type, String payload) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, type, candidateUuid, applicationUuid,
                        positionUuid, "USER", actorUuid, "NORMAL", payload, null));
    }
}
