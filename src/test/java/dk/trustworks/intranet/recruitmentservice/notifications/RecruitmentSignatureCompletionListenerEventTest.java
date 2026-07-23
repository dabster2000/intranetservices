package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentOfferBridge;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.batch.runtime.context.JobContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;

/**
 * P10 completion-listener event append (contract B1c/B3.5): a COMPLETED
 * signing case joined to a dossier revision — WITHOUT any SharePoint upload
 * status, which recruitment-created cases never get — yields exactly one
 * {@code SIGNING_COMPLETED} event; a second {@code doProcess} run (the
 * restart simulation: durable idempotency must not rely on the in-memory
 * email dedup set) still yields exactly one. The email path is untouched:
 * no HR mail fires for a non-UPLOADED case.
 * <p>
 * The listener is constructed manually (not CDI-injected) so the test
 * controls its collaborators: the real {@code EntityManager}, real
 * {@code Config}, the real {@code RecruitmentOfferBridge} and a Mockito
 * {@code MailResource}. {@code doProcess} is called inside an explicit
 * transaction, mirroring its {@code @Transactional} binding.
 */
@QuarkusTest
class RecruitmentSignatureCompletionListenerEventTest {

    /**
     * Target company whose HR recipient list is configured in
     * src/test/resources/application.properties
     * ({@code recruitment.completion-notification.<this-uuid>}).
     */
    private static final String CONFIGURED_COMPANY_UUID = "11111111-1111-1111-1111-111111111111";

    @Inject
    EntityManager em;

    @Inject
    RecruitmentOfferBridge offerBridge;

    @Inject
    Config config;

    private RecruitmentSignatureCompletionListener listener;
    private MailResource mailResource;

    private String candidateUuid;
    private String dossierUuid;
    private String caseKey;

    @BeforeEach
    void setUp() throws Exception {
        listener = new RecruitmentSignatureCompletionListener();
        listener.em = em;
        listener.config = config;
        listener.offerBridge = offerBridge;
        mailResource = Mockito.mock(MailResource.class);
        listener.mailResource = mailResource;
        // MonitoredBatchlet.reportNonFatalError dereferences the JBeret
        // JobContext outside its own try/catch — outside a real job run it
        // is null, so a mocked context keeps per-case error reporting inert.
        Field jobContext = MonitoredBatchlet.class.getDeclaredField("jobContext");
        jobContext.setAccessible(true);
        jobContext.set(listener, Mockito.mock(JobContext.class));

        candidateUuid = UUID.randomUUID().toString();
        dossierUuid = UUID.randomUUID().toString();
        caseKey = "listener-test-case-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, status, source,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, :first, 'Fixture', 'ACTIVE', 'OTHER',
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("first", PII_SENTINEL + "-Listener")
                    .setParameter("actor", UUID.randomUUID().toString()).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, status)
                            VALUES (:uuid, :candidate, :template, 'OPEN')
                            """)
                    .setParameter("uuid", dossierUuid)
                    .setParameter("candidate", candidateUuid)
                    .setParameter("template", UUID.randomUUID().toString()).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossier_revisions
                                (uuid, dossier_uuid, version_number, kind,
                                 placeholder_values_snapshot, signers_config_snapshot,
                                 appendices_snapshot, signing_case_key,
                                 recipient_email, sent_by_useruuid)
                            VALUES (:uuid, :dossier, 1, 'SIGNATURE', '{}', '[]', '[]',
                                    :caseKey, 'fixture@example.com', :sender)
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("dossier", dossierUuid)
                    .setParameter("caseKey", caseKey)
                    .setParameter("sender", UUID.randomUUID().toString()).executeUpdate();
            // COMPLETED but NO sharepoint_upload_status — the shape every
            // recruitment-created case has (upload is owned by Convert).
            em.createNativeQuery("""
                            INSERT INTO signing_cases
                                (case_key, user_uuid, document_name, status,
                                 sharepoint_upload_status, created_at)
                            VALUES (:caseKey, :user, 'Listener Fixture Contract', 'COMPLETED',
                                    NULL, NOW())
                            """)
                    .setParameter("caseKey", caseKey)
                    .setParameter("user", UUID.randomUUID().toString()).executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM signing_cases WHERE case_key = :ck")
                    .setParameter("ck", caseKey).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossier_revisions WHERE dossier_uuid = :d")
                    .setParameter("d", dossierUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE uuid = :d")
                    .setParameter("d", dossierUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
        });
    }

    @Test
    void doProcess_appendsExactlyOneSigningCompleted_evenAcrossReruns_emailUntouched() {
        runListener();

        List<RecruitmentEvent> afterFirst = signingCompletedEvents();
        assertEquals(1, afterFirst.size(),
                "one COMPLETED dossier-linked case → exactly one SIGNING_COMPLETED");
        RecruitmentEvent event = afterFirst.get(0);
        assertEquals(RecruitmentActorType.SYSTEM, event.getActorType());
        assertTrue(event.getPayload().contains("\"case_key\":\"" + caseKey + "\""));
        assertTrue(event.getPayload().contains("\"dossier_uuid\":\"" + dossierUuid + "\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);

        // Second run — the restart simulation. The batchlet's in-memory
        // email dedup set does not protect events; only the durable
        // event-store check does. Still exactly one event afterwards.
        runListener();
        assertEquals(1, signingCompletedEvents().size(),
                "durable idempotency: a rerun (deploy/restart) must not duplicate the timeline");

        // Email path: this fixture's candidate has no target_company_uuid, so
        // recipient resolution finds no configured address and the email loop
        // skips it silently. (The UPLOADED gate is gone — the case now MATCHES
        // the email query; it simply has nowhere to send.) So no HR mail may
        // reference this candidate. Scoped to our fixture — the shared local DB
        // can legitimately contain other dossier-linked completed cases the
        // email path picks up in the same run. The configured-recipient email
        // path is proven by doProcess_recruitmentCaseWithNullUpload_... below.
        ArgumentCaptor<TrustworksMail> mails = ArgumentCaptor.forClass(TrustworksMail.class);
        Mockito.verify(mailResource, atLeast(0)).sendingHTML(mails.capture());
        boolean mailedForOurCase = mails.getAllValues().stream()
                .anyMatch(m -> m.getSubject() != null && m.getSubject().contains(PII_SENTINEL + "-Listener"));
        assertTrue(!mailedForOurCase,
                "no completion email may fire when no recipient is configured for the company");
    }

    /**
     * The dormant-email fix (deviation 3 / follow-up 4): a recruitment-created
     * COMPLETED case with a NULL sharepoint_upload_status — the shape every
     * send-signature case has — now triggers exactly ONE HR courtesy email
     * once a recipient is configured for the candidate's target company. A
     * second {@code doProcess} run (same JVM) sends no duplicate: the existing
     * in-memory {@code NOTIFIED_CASE_KEYS} dedup holds. The SIGNING_COMPLETED
     * event-append loop is unaffected — still exactly one event.
     */
    @Test
    void doProcess_recruitmentCaseWithNullUpload_sendsExactlyOneEmail_andNoDuplicateOnRerun() {
        // Point the fixture candidate at the company whose recipient list is
        // configured in src/test/resources/application.properties so recipient
        // resolution yields an address instead of skipping silently.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "UPDATE recruitment_candidates SET target_company_uuid = :company WHERE uuid = :uuid")
                        .setParameter("company", CONFIGURED_COMPANY_UUID)
                        .setParameter("uuid", candidateUuid)
                        .executeUpdate());

        runListener();
        runListener();

        // Exactly one email for OUR case across both runs → the query now
        // matches a NULL-upload case (fix) AND the rerun did not duplicate it
        // (in-memory dedup). Scoped to our fixture's subject sentinel because
        // the shared DB may hold other configured cases.
        ArgumentCaptor<TrustworksMail> mails = ArgumentCaptor.forClass(TrustworksMail.class);
        Mockito.verify(mailResource, atLeast(0)).sendingHTML(mails.capture());
        long mailedForOurCase = mails.getAllValues().stream()
                .filter(m -> m.getSubject() != null && m.getSubject().contains(PII_SENTINEL + "-Listener"))
                .count();
        assertEquals(1, mailedForOurCase,
                "a NULL-upload recruitment case must email HR exactly once, with no duplicate on rerun");

        // Event loop unchanged: still exactly one SIGNING_COMPLETED.
        assertEquals(1, signingCompletedEvents().size(),
                "email-path change must not affect the SIGNING_COMPLETED event loop");
    }

    private void runListener() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            try {
                listener.doProcess();
            } catch (Exception e) {
                throw new RuntimeException("doProcess failed", e);
            }
        });
    }

    private List<RecruitmentEvent> signingCompletedEvents() {
        em.clear();
        return RecruitmentEvent.list(
                "candidateUuid = ?1 and eventType = ?2 order by seq",
                candidateUuid,
                dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.SIGNING_COMPLETED);
    }
}
