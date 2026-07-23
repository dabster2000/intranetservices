package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P12 §DoD referrer cadence: exactly the four milestone DMs across a full
 * funnel run (screening → interviewing → offer → outcome), none for
 * non-referral candidates, no re-notification on micro-moves or
 * back-moves, outcome variants (hired / not proceeding / talent pool),
 * fail-quiet on missing Slack links, and the durable
 * {@code REFERRAL_OUTCOME_NOTIFIED} bookkeeping with a PII-clean payload.
 * Raw-inserted events + deterministic {@code catchUp()} — the chassis'
 * crash-recovery path, same harness as the AI reactor tests.
 */
@QuarkusTest
class ReferrerNotificationReactorTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;

    @Inject
    EntityManager em;

    @Inject
    ReferrerNotificationReactor reactor;

    @InjectMock
    SlackService slackService;

    private String practiceUuid;
    private String referrerUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String referralUuid;

    private String previousPipeline;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        referrerUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, referrerUuid, "Rasmus", "Referrer");
            P12NotificationFixtures.setUserSlackLink(em, referrerUuid, "U-RASMUS");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Jens", "Hansen", "ACTIVE", null, null, referrerUuid);
            P12NotificationFixtures.setCandidateReferredBy(em, candidateUuid, referrerUuid);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");
            referralUuid = P12NotificationFixtures.insertReferral(em, referrerUuid,
                    "Jens Hansen", "great colleague", "TRIAGED", candidateUuid);
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
        });
        reactor.catchUp(); // drain backlog with the flag off
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true"));
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P12NotificationFixtures.deleteReferralsBy(em, referrerUuid);
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid), List.of(referrerUuid), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipeline);
        });
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void event(String type, String candidate, String application, String payload) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, type, candidate, application,
                        application == null ? null : positionUuid,
                        "USER", referrerUuid, "NORMAL", payload, null));
    }

    private void stageMove(String toStage) {
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setApplicationStage(em, applicationUuid, toStage));
        event("APPLICATION_STAGE_CHANGED", candidateUuid, applicationUuid,
                "{\"to\":\"" + toStage + "\",\"direction\":\"FORWARD\"}");
    }

    private List<String> dms(int expected) throws Exception {
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(expected)).sendMessage(any(User.class), texts.capture());
        return texts.getAllValues();
    }

    private List<RecruitmentEvent> bookkeepingEvents() {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return em.createQuery("SELECT e FROM RecruitmentEvent e WHERE e.candidateUuid = :c "
                            + "AND e.eventType = :t ORDER BY e.seq", RecruitmentEvent.class)
                    .setParameter("c", candidateUuid)
                    .setParameter("t", RecruitmentEventType.REFERRAL_OUTCOME_NOTIFIED)
                    .getResultList();
        });
    }

    // ---- The full funnel: exactly four milestone DMs ------------------------------

    @Test
    void fullFunnel_exactlyFourMilestoneDMs_withDurableBookkeeping() throws Exception {
        // 1. Screening entered (application created in SCREENING).
        event("APPLICATION_CREATED", candidateUuid, applicationUuid,
                "{\"initial_stage\":\"SCREENING\",\"origin\":\"manual\"}");
        reactor.catchUp();

        // 2. Interviewing — INTERVIEW_2 after INTERVIEW_1 is a micro-move, no second DM.
        stageMove("INTERVIEW_1");
        stageMove("INTERVIEW_2");
        reactor.catchUp();

        // 3. Offer.
        stageMove("OFFER");
        reactor.catchUp();

        // Back-move: INTERVIEWING was already notified — stays silent.
        stageMove("INTERVIEW_2");
        reactor.catchUp();
        verify(slackService, times(3)).sendMessage(any(User.class), anyString());

        // 4. Outcome: hired.
        QuarkusTransaction.requiringNew().run(() -> {
            P12NotificationFixtures.setApplicationStage(em, applicationUuid, "HIRED");
            P12NotificationFixtures.setCandidateStatus(em, candidateUuid, "HIRED");
        });
        event("CANDIDATE_HIRED", candidateUuid, applicationUuid,
                "{\"user_uuid\":\"" + UUID.randomUUID() + "\"}");
        reactor.catchUp();

        List<String> texts = dms(4);
        assertTrue(texts.get(0).contains("entered screening"), texts.get(0));
        assertTrue(texts.get(1).contains("is now interviewing"), texts.get(1));
        assertTrue(texts.get(2).contains("has received an offer"), texts.get(2));
        assertTrue(texts.get(3).contains("has been hired"), texts.get(3));
        // Milestone-level means milestone-only: never position or stage codes (§P6 rule).
        for (String text : texts) {
            assertTrue(text.contains("Jens Hansen"), "DM names the referred candidate");
            assertFalse(text.contains("Senior Consultant"), "DM must not carry the position");
            assertFalse(text.contains("INTERVIEW_"), "DM must not carry stage codes");
        }

        List<RecruitmentEvent> bookkeeping = bookkeepingEvents();
        assertEquals(4, bookkeeping.size(), "one durable bookkeeping event per DM");
        List<String> milestones = bookkeeping.stream()
                .map(e -> e.getPayload().contains("IN_SCREENING") ? "IN_SCREENING"
                        : e.getPayload().contains("INTERVIEWING") ? "INTERVIEWING"
                        : e.getPayload().contains("OFFER") ? "OFFER"
                        : e.getPayload().contains("HIRED") ? "HIRED" : "?")
                .toList();
        assertEquals(List.of("IN_SCREENING", "INTERVIEWING", "OFFER", "HIRED"), milestones);
        for (RecruitmentEvent e : bookkeeping) {
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(e);
            assertTrue(e.getPayload().contains(referralUuid), "bookkeeping links the referral row");
        }
    }

    // ---- Guards --------------------------------------------------------------------

    @Test
    void nonReferredCandidate_getsNoDM() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET referred_by_user_uuid = NULL "
                                + "WHERE uuid = :uuid")
                        .setParameter("uuid", candidateUuid).executeUpdate());
        event("APPLICATION_CREATED", candidateUuid, applicationUuid, "{\"origin\":\"manual\"}");

        reactor.catchUp();

        verifyNoInteractions(slackService);
    }

    @Test
    void pipelineFlagOff_noDM_offsetAdvances() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false"));
        event("APPLICATION_CREATED", candidateUuid, applicationUuid, "{\"origin\":\"manual\"}");

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertEquals(0, bookkeepingEvents().size());
    }

    @Test
    void missingSlackLink_skipsQuietly_noBookkeepingEvent() {
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setUserSlackLink(em, referrerUuid, ""));
        event("APPLICATION_CREATED", candidateUuid, applicationUuid, "{\"origin\":\"manual\"}");

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertEquals(0, bookkeepingEvents().size(),
                "no 'notified' bookkeeping when nothing was sent");
        assertTrue(reactor.watermark() > 0, "the event is processed, not stuck");
    }

    @Test
    void duplicateDelivery_producesOneDM() throws Exception {
        event("APPLICATION_CREATED", candidateUuid, applicationUuid, "{\"origin\":\"manual\"}");
        reactor.catchUp();
        reactor.catchUp(); // replay sweep

        // Derived dedupe on top of the chassis dedupe: a second event
        // arriving at the same milestone also stays silent.
        event("APPLICATION_CREATED", candidateUuid, applicationUuid, "{\"origin\":\"manual\"}");
        reactor.catchUp();

        dms(1);
        assertEquals(1, bookkeepingEvents().size());
    }

    // ---- Outcome variants -------------------------------------------------------------

    @Test
    void rejection_notifiesNotProceeding() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setApplicationTerminal(em, applicationUuid, "REJECTED"));
        event("APPLICATION_REJECTED", candidateUuid, applicationUuid,
                "{\"reason_code\":\"TIMING\",\"from_stage\":\"SCREENING\"}");

        reactor.catchUp();

        List<String> texts = dms(1);
        assertTrue(texts.get(0).contains("not proceeding"), texts.get(0));
        assertFalse(texts.get(0).contains("TIMING"), "rejection reasons never reach the referrer");
    }

    @Test
    void returnToPool_notifiesTalentPool() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            P12NotificationFixtures.setApplicationTerminal(em, applicationUuid, "RETURNED_TO_POOL");
            P12NotificationFixtures.setCandidateStatus(em, candidateUuid, "POOLED");
        });
        event("CANDIDATE_POOLED", candidateUuid, applicationUuid,
                "{\"terminal\":\"RETURNED_TO_POOL\",\"from_stage\":\"SCREENING\"}");

        reactor.catchUp();

        List<String> texts = dms(1);
        assertTrue(texts.get(0).contains("talent pool"), texts.get(0));
    }

    // ---- Isolation between referrals ----------------------------------------------------

    @Test
    void dmNeverMentionsOtherCandidates() throws Exception {
        String otherCandidate = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertCandidate(em, otherCandidate,
                    "Ove", "Other", "ACTIVE", null, null, referrerUuid);
            P12NotificationFixtures.setCandidateReferredBy(em, otherCandidate, referrerUuid);
        });
        try {
            event("APPLICATION_CREATED", candidateUuid, applicationUuid, "{\"origin\":\"manual\"}");
            reactor.catchUp();

            List<String> texts = dms(1);
            assertTrue(texts.get(0).contains("Jens Hansen"));
            assertFalse(texts.get(0).contains("Ove Other"),
                    "a milestone DM must never mention another candidate");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(otherCandidate), List.of(), List.of(), null));
        }
    }
}
