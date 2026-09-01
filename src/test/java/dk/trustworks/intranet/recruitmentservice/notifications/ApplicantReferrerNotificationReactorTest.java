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
 * Change request (e), 2026-09-01 — the disclosure notice: exactly ONE DM to
 * the employee an applicant named, never a second on redelivery, nothing at
 * all while the launch-gate flag is off or when the typed name matched
 * nobody, and PII-clean bookkeeping. Raw-inserted events + deterministic
 * {@code catchUp()}, the same harness as {@link ReferrerNotificationReactorTest}.
 */
@QuarkusTest
class ApplicantReferrerNotificationReactorTest {

    private static final String CLAIM_FLAG = "recruitment.apply.referrer-claim.enabled";

    @Inject
    EntityManager em;

    @Inject
    ApplicantReferrerNotificationReactor reactor;

    @InjectMock
    SlackService slackService;

    private String namedUserUuid;
    private String candidateUuid;
    private String previousFlag;

    @BeforeEach
    void seed() {
        namedUserUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, namedUserUuid, "Rasmus", "Kollega");
            P12NotificationFixtures.setUserSlackLink(em, namedUserUuid, "U-RASMUS");
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Mette", "Krogh", "ACTIVE", null, null, "public-form");
            P12NotificationFixtures.setCandidateReferredBy(em, candidateUuid, namedUserUuid);
            previousFlag = P8ProfileFixtures.setFlag(em, CLAIM_FLAG, "false");
        });
        reactor.catchUp(); // drain the backlog with the gate closed
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, CLAIM_FLAG, "true"));
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(), List.of(namedUserUuid), null);
            P8ProfileFixtures.restoreFlag(em, CLAIM_FLAG, previousFlag);
        });
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void claimEvent(String matchedUserUuid) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICANT_REFERRER_CLAIMED", candidateUuid,
                        null, null, "CANDIDATE", null, "NORMAL",
                        "{\"origin\":\"public_form\",\"match_method\":\"name\""
                                + (matchedUserUuid == null ? ""
                                : ",\"matched_user_uuid\":\"" + matchedUserUuid + "\"")
                                + "}",
                        "{\"claimed_name\":\"Rasmus Kollega\"}"));
    }

    private List<RecruitmentEvent> bookkeepingEvents() {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return em.createQuery("SELECT e FROM RecruitmentEvent e WHERE e.candidateUuid = :c "
                            + "AND e.eventType = :t ORDER BY e.seq", RecruitmentEvent.class)
                    .setParameter("c", candidateUuid)
                    .setParameter("t", RecruitmentEventType.APPLICANT_REFERRER_NOTIFIED)
                    .getResultList();
        });
    }

    // ---- The happy path ----------------------------------------------------------

    @Test
    void matchedClaim_sendsExactlyOneHonestNotice() throws Exception {
        claimEvent(namedUserUuid);
        reactor.catchUp();
        reactor.catchUp(); // redelivery must not DM twice

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).sendMessage(any(User.class), texts.capture());
        String dm = texts.getValue();
        assertTrue(dm.contains("*Mette Krogh*"), dm);
        assertTrue(dm.contains("ansøgerens egen oplysning"), dm);
        assertTrue(dm.contains("ikke en anbefaling fra dig"), dm);
        assertFalse(dm.contains("Your referral"),
                "the referral cadence's voice would tell the recipient a lie");

        List<RecruitmentEvent> bookkeeping = bookkeepingEvents();
        assertEquals(1, bookkeeping.size(), "one durable bookkeeping event per DM");
        assertTrue(bookkeeping.get(0).getPayload().contains(namedUserUuid));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(bookkeeping.get(0));
    }

    // ---- Guards --------------------------------------------------------------------

    @Test
    void flagOff_isSilent_andDoesNotBackfillWhenTurnedOn() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, CLAIM_FLAG, "false"));
        claimEvent(namedUserUuid);
        reactor.catchUp();
        verifyNoInteractions(slackService);

        // The launch gate is a gate, not a queue: enabling it later must not
        // suddenly DM everyone named while the policy was still unpublished.
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, CLAIM_FLAG, "true"));
        reactor.catchUp();
        verifyNoInteractions(slackService);
    }

    @Test
    void unmatchedClaim_notifiesNobody() {
        // The typed name was kept as an external referrer name; there is no
        // employee to tell, and guessing a recipient is exactly what the
        // matcher refuses to do.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates "
                                + "SET referred_by_user_uuid = NULL WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate());
        claimEvent(null);
        reactor.catchUp();

        verifyNoInteractions(slackService);
    }

    @Test
    void namedEmployeeWithoutSlack_failsQuiet_andStaysRedeliverable() {
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setUserSlackLink(em, namedUserUuid, null));
        claimEvent(namedUserUuid);
        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertTrue(bookkeepingEvents().isEmpty(),
                "no DM went out, so nothing may be bookkept as notified");
    }

    @Test
    void unrelatedEvents_areIgnored() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "CANDIDATE_CREATED", candidateUuid,
                        null, null, "CANDIDATE", null, "NORMAL",
                        "{\"origin\":\"public_form\"}", null));
        reactor.catchUp();

        verify(slackService, times(0)).sendMessage(any(User.class), anyString());
    }
}
