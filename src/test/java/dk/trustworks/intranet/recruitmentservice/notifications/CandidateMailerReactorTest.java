package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15 DoD — the candidate mailer against the real chassis (raw-inserted
 * committed events + deterministic {@code catchUp()} sweeps) and the real
 * local DB:
 * <ul>
 *   <li>acknowledgement fires for public-form applications only;</li>
 *   <li>no duplicate sends under catch-up replay (chassis dedupe);</li>
 *   <li>rejection variants: screening vs post-interview templates;
 *       review-first templates queue instead of sending;</li>
 *   <li>partner-referral rejections NEVER auto-send;</li>
 *   <li>stage-triggered templates fire on forward entries only;</li>
 *   <li>flag off ⇒ silent advance; no email address ⇒ visible skip;</li>
 *   <li>EMAIL_SENT payload is structural only (pii carries recipient,
 *       subject, body); trigger visibility is copied.</li>
 * </ul>
 */
@QuarkusTest
class CandidateMailerReactorTest {

    private static final String INTERVIEWS_FLAG = "recruitment.interviews.enabled";

    @Inject
    EntityManager em;

    @Inject
    CandidateMailerReactor reactor;

    private String practiceUuid;
    private String actorUser;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String candidateEmail;

    private String previousFlag;
    private String stageTemplateUuid;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        actorUser = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        candidateEmail = "anna." + UUID.randomUUID() + "@example.com";
        stageTemplateUuid = null;

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, actorUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Løsningsarkitekt",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Søren", "Kjærgård", "ACTIVE", null, null, actorUser);
            setCandidateEmail(candidateUuid, candidateEmail);
            P12NotificationFixtures.setCandidateSource(em, candidateUuid, "WEBSITE");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");
            previousFlag = P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "false");
        });
        // Drain any backlog with the flag OFF so each test's sweep only
        // reflects its own trigger events.
        reactor.catchUp();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_pending_emails WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            if (stageTemplateUuid != null) {
                em.createNativeQuery("DELETE FROM recruitment_email_templates WHERE uuid = :u")
                        .setParameter("u", stageTemplateUuid).executeUpdate();
            }
            // Restore the V521 state: both receipt templates inactive.
            em.createNativeQuery("UPDATE recruitment_email_templates SET active = 0 "
                            + "WHERE template_key IN "
                            + "('UNSOLICITED_ACKNOWLEDGEMENT', 'DUPLICATE_APPLICATION_NOTICE')")
                    .executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid), List.of(actorUser), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, INTERVIEWS_FLAG, previousFlag);
        });
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void flagOn() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "true"));
    }

    private void setCandidateEmail(String uuid, String email) {
        em.createNativeQuery("UPDATE recruitment_candidates SET email = :email WHERE uuid = :uuid")
                .setParameter("email", email)
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private long insertApplicationCreated(String origin, String visibility) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, visibility,
                        "{\"position_title\":\"Løsningsarkitekt\",\"initial_stage\":\"SCREENING\","
                                + "\"origin\":\"" + origin + "\"}",
                        "{\"note\":\"" + PII_SENTINEL + "\"}"));
    }

    private long insertRejected(String fromStage) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_REJECTED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, "NORMAL",
                        "{\"reason_code\":\"PROFILE_MISMATCH\",\"from_stage\":\"" + fromStage + "\"}",
                        "{\"note\":\"" + PII_SENTINEL + "\"}"));
    }

    private long insertStageChanged(String to, String direction) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_STAGE_CHANGED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, "NORMAL",
                        "{\"from\":\"SCREENING\",\"to\":\"" + to + "\",\"direction\":\""
                                + direction + "\",\"skipped_stages\":[]}", null));
    }

    private long mailCount() {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mail WHERE mail = :to")
                        .setParameter("to", candidateEmail)
                        .getSingleResult()).longValue());
    }

    private List<RecruitmentEvent> emailSentEvents() {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.list("candidateUuid = ?1 and eventType = ?2 order by seq",
                        candidateUuid, RecruitmentEventType.EMAIL_SENT));
    }

    private List<RecruitmentPendingEmail> pendingRows() {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentPendingEmail.list("candidateUuid = ?1", candidateUuid));
    }

    // ---- Flag gating -----------------------------------------------------------

    @Test
    void interviewsFlagOff_noMail_offsetStillAdvances() {
        long seq = insertApplicationCreated("public_form", "NORMAL");

        reactor.catchUp();

        assertEquals(0, mailCount());
        assertTrue(emailSentEvents().isEmpty());
        assertTrue(reactor.watermark() >= seq,
                "flag-off events must advance the offset (no backfill)");
    }

    // ---- Acknowledgement (public-source only) ------------------------------------

    @Test
    void publicFormApplication_sendsAcknowledgement_mailRowAndEventCommitted() {
        flagOn();
        insertApplicationCreated("public_form", "NORMAL");

        reactor.catchUp();

        assertEquals(1, mailCount());
        List<RecruitmentEvent> events = emailSentEvents();
        assertEquals(1, events.size());
        RecruitmentEvent event = events.get(0);
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        assertTrue(event.getPayload().contains("\"template_key\":\"ACKNOWLEDGEMENT\""));
        assertTrue(event.getPayload().contains("\"trigger\":\"AUTO\""));
        // Rendered Danish content (merge fields resolved) lives in pii.
        assertTrue(event.getPii().contains("Søren"));
        assertTrue(event.getPii().contains("Løsningsarkitekt"));
        assertTrue(event.getPii().contains(candidateEmail));
        assertEquals("SYSTEM", event.getActorType().name());

        // The queued mail body is rendered HTML with the Danish characters intact.
        String body = QuarkusTransaction.requiringNew().call(() ->
                (String) em.createNativeQuery("SELECT content FROM mail WHERE mail = :to")
                        .setParameter("to", candidateEmail)
                        .getSingleResult());
        assertTrue(body.contains("Kære Søren"));
        assertTrue(body.contains("Løsningsarkitekt"));
        assertFalse(body.contains("{{"), "no unrendered merge fields in the outgoing mail");
    }

    @Test
    void manualOriginApplication_noAcknowledgement() {
        flagOn();
        insertApplicationCreated("manual", "NORMAL");

        reactor.catchUp();

        assertEquals(0, mailCount());
        assertTrue(emailSentEvents().isEmpty());
    }

    @Test
    void replayThroughCatchUpAndLiveDuplicate_exactlyOneMail() {
        flagOn();
        long seq = insertApplicationCreated("public_form", "NORMAL");

        reactor.catchUp();
        // A racing live delivery of the same event and a full re-sweep.
        reactor.deliverLive(seq);
        reactor.catchUp();

        assertEquals(1, mailCount());
        assertEquals(1, emailSentEvents().size());
    }

    @Test
    void candidateWithoutEmail_visibleSkip_offsetAdvances() {
        flagOn();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET email = NULL WHERE uuid = :uuid")
                        .setParameter("uuid", candidateUuid).executeUpdate());
        long seq = insertApplicationCreated("public_form", "NORMAL");

        reactor.catchUp();

        assertEquals(0, mailCount());
        assertTrue(emailSentEvents().isEmpty());
        assertTrue(pendingRows().isEmpty());
        assertTrue(reactor.watermark() >= seq);
    }

    // ---- Rejection variants -------------------------------------------------------

    @Test
    void rejectionFromScreening_autoSendsScreeningVariant() {
        flagOn();
        insertRejected("SCREENING");

        reactor.catchUp();

        assertEquals(1, mailCount());
        List<RecruitmentEvent> events = emailSentEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).getPayload().contains("\"template_key\":\"REJECTION_SCREENING\""));
        assertTrue(pendingRows().isEmpty());
    }

    @Test
    void rejectionAfterInterview_queuesReviewFirstVariant_noAutoSend() {
        flagOn();
        insertRejected("INTERVIEW_2");

        reactor.catchUp();

        assertEquals(0, mailCount());
        assertTrue(emailSentEvents().isEmpty());
        List<RecruitmentPendingEmail> pending = pendingRows();
        assertEquals(1, pending.size());
        assertEquals("REJECTION_POST_INTERVIEW", pending.get(0).getTemplateKey());
        assertEquals(RecruitmentPendingEmailReason.REVIEW_FIRST_TEMPLATE, pending.get(0).getReason());
        assertEquals(RecruitmentPendingEmailStatus.PENDING, pending.get(0).getStatus());
        assertEquals(candidateEmail, pending.get(0).getToEmail());
        // Rendered snapshot, not the raw template.
        assertTrue(pending.get(0).getBody().contains("Søren"));
        assertFalse(pending.get(0).getBody().contains("{{"));
    }

    @Test
    void partnerReferralRejection_neverAutoSends_evenFromScreening() {
        flagOn();
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setCandidateSource(em, candidateUuid, "PARTNER_REFERRAL"));
        insertRejected("SCREENING");

        reactor.catchUp();

        assertEquals(0, mailCount());
        assertTrue(emailSentEvents().isEmpty());
        List<RecruitmentPendingEmail> pending = pendingRows();
        assertEquals(1, pending.size());
        assertEquals("REJECTION_SCREENING", pending.get(0).getTemplateKey());
        assertEquals(RecruitmentPendingEmailReason.PARTNER_REFERRAL, pending.get(0).getReason());
    }

    @Test
    void reviewFirstReplay_singlePendingRow() {
        flagOn();
        long seq = insertRejected("INTERVIEW_1");

        reactor.catchUp();
        reactor.deliverLive(seq);
        reactor.catchUp();

        assertEquals(1, pendingRows().size());
    }

    // ---- Stage-triggered templates --------------------------------------------------

    @Test
    void stageTemplate_firesOnForwardEntry_notOnBackMove_notWithoutTemplate() {
        flagOn();
        // STAGE_INTERVIEW_3 deliberately: V521 seeds a real (inactive)
        // STAGE_OFFER row, and template_key is unique — this test needs a
        // stage key nothing seeds so it can own the row it inserts.
        insertStageChanged("INTERVIEW_3", "FORWARD");
        reactor.catchUp();
        assertEquals(0, mailCount());

        stageTemplateUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_email_templates
                                    (uuid, template_key, name, subject, body, auto_send, active,
                                     created_at, updated_at, created_by)
                                VALUES (:uuid, 'STAGE_INTERVIEW_3', 'Tredje samtale', 'Vi vil gerne videre med dig',
                                        'Kære {{candidate_first_name}}\\n\\nVi vil gerne se dig igen.',
                                        1, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'test')
                                """)
                        .setParameter("uuid", stageTemplateUuid)
                        .executeUpdate());

        insertStageChanged("INTERVIEW_3", "FORWARD");
        reactor.catchUp();
        assertEquals(1, mailCount());
        assertTrue(emailSentEvents().get(0).getPayload()
                .contains("\"template_key\":\"STAGE_INTERVIEW_3\""));

        // Back-moves never mail the candidate.
        insertStageChanged("INTERVIEW_3", "BACK");
        reactor.catchUp();
        assertEquals(1, mailCount());
    }

    // ---- Receipts with no application (remediation F6/F7) ----------------------------

    /**
     * Make a receipt template exist AND be live for one test. V521 seeds
     * both receipt keys inactive (TA activates after sign-off), so the row
     * usually exists — INSERT IGNORE covers a pre-V521 database, the UPDATE
     * makes it fire. {@link #cleanup()} always flips both keys back to
     * inactive, restoring the V521 state.
     */
    private void activateReceiptTemplate(String key, String subject, String body) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT IGNORE INTO recruitment_email_templates
                                (uuid, template_key, name, subject, body, body_format, auto_send,
                                 active, created_at, updated_at, created_by)
                            VALUES (:uuid, :key, :key, :subject, :body, 'HTML', 1, 0,
                                    UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'test')
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("key", key)
                    .setParameter("subject", subject)
                    .setParameter("body", body)
                    .executeUpdate();
            em.createNativeQuery("UPDATE recruitment_email_templates "
                            + "SET auto_send = 1, active = 1 WHERE template_key = :key")
                    .setParameter("key", key)
                    .executeUpdate();
        });
    }

    private long insertUnsolicitedReceived() {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "UNSOLICITED_APPLICATION_RECEIVED", candidateUuid,
                        null, null, "USER", actorUser, "NORMAL",
                        "{\"origin\":\"public_form\",\"candidate_reused\":false}", null));
    }

    private long insertDuplicateReceived() {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "DUPLICATE_APPLICATION_RECEIVED", candidateUuid,
                        null, positionUuid, "USER", actorUser, "NORMAL",
                        "{\"origin\":\"public_form\",\"reason\":\"DUPLICATE_PUBLIC_SUBMISSION\"}",
                        null));
    }

    @Test
    void unsolicitedSubmission_sendsTheUnsolicitedAcknowledgement() {
        flagOn();
        activateReceiptTemplate(
                dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService
                        .KEY_UNSOLICITED_ACKNOWLEDGEMENT,
                "Vi har modtaget din uopfordrede ansøgning",
                "<p>Kære {{candidate_first_name}}</p><p>Tak — du hører fra os.</p>");
        insertUnsolicitedReceived();

        reactor.catchUp();

        assertEquals(1, mailCount());
        List<RecruitmentEvent> events = emailSentEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).getPayload()
                .contains("\"template_key\":\"UNSOLICITED_ACKNOWLEDGEMENT\""));
        String body = QuarkusTransaction.requiringNew().call(() ->
                (String) em.createNativeQuery("SELECT content FROM mail WHERE mail = :to")
                        .setParameter("to", candidateEmail)
                        .getSingleResult());
        assertTrue(body.contains("Kære Søren"), "merge fields resolve without an application");
    }

    @Test
    void duplicateSubmission_sendsTheDuplicateApplicationNotice() {
        flagOn();
        activateReceiptTemplate(
                dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService
                        .KEY_DUPLICATE_APPLICATION_NOTICE,
                "Vi har modtaget dine dokumenter",
                "<p>Kære {{candidate_first_name}}</p><p>Du er allerede i proces hos os.</p>");
        insertDuplicateReceived();

        reactor.catchUp();

        assertEquals(1, mailCount());
        List<RecruitmentEvent> events = emailSentEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).getPayload()
                .contains("\"template_key\":\"DUPLICATE_APPLICATION_NOTICE\""));
    }

    @Test
    void receiptEvent_withoutActiveTemplate_silentAdvance() {
        // The V521 default: the templates exist but are inactive until TA
        // signs off the copy — the trigger stays quietly off, offset advances.
        flagOn();
        long seq = insertUnsolicitedReceived();

        reactor.catchUp();

        assertEquals(0, mailCount());
        assertTrue(emailSentEvents().isEmpty());
        assertTrue(reactor.watermark() >= seq);
    }

    // ---- Visibility ------------------------------------------------------------------

    @Test
    void circleTriggerEvent_emailSentStaysCircleScoped() {
        flagOn();
        insertApplicationCreated("public_form", "CIRCLE");

        reactor.catchUp();

        List<RecruitmentEvent> events = emailSentEvents();
        assertEquals(1, events.size());
        assertEquals(RecruitmentEventVisibility.CIRCLE, events.get(0).getVisibility());
    }
}
