package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15 DoD (API, end-to-end through the resource with real {@code roles}
 * fixtures resolved via {@code X-Requested-By}):
 * <ul>
 *   <li>template CRUD with recruiter-tier gating (teamlead → 404) and
 *       explicit validation (§P4.9);</li>
 *   <li>compose render + manual send — mail row and {@code EMAIL_SENT}
 *       asserted against the DB in a fresh transaction (the §P11 flush
 *       lesson), recruiter as actor;</li>
 *   <li>review queue: approve sends-and-logs exactly once (one-shot;
 *       second attempt → 409), dismiss sends nothing;</li>
 *   <li>partner-circle hard filter: a queue row for a partner-track-only
 *       candidate is invisible to a non-circle recruiter (the P8 read
 *       matrix applied to the queue).</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentEmailResourceApiTest {

    private static final String INTERVIEWS_FLAG = "recruitment.interviews.enabled";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String recruiterUser;
    private String teamleadUser;
    private String adminUser;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String candidateEmail;

    private String partnerPositionUuid;
    private String partnerCandidateUuid;
    private String partnerApplicationUuid;

    private String previousFlag;
    private String customTemplateKey;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        candidateEmail = "kandidat." + UUID.randomUUID() + "@example.com";
        partnerPositionUuid = UUID.randomUUID().toString();
        partnerCandidateUuid = UUID.randomUUID().toString();
        partnerApplicationUuid = UUID.randomUUID().toString();
        customTemplateKey = "TEST_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12).toUpperCase();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, recruiterUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, teamleadUser, "Tim", "Teamlead");
            P8ProfileFixtures.insertUser(em, adminUser, "Ada", "Admin");
            P8ProfileFixtures.insertRole(em, recruiterUser, "HR");
            P8ProfileFixtures.insertRole(em, teamleadUser, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Løsningsarkitekt",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Søren", "Kjærgård", "ACTIVE", null, null, recruiterUser);
            em.createNativeQuery(
                            "UPDATE recruitment_candidates SET email = :email WHERE uuid = :uuid")
                    .setParameter("email", candidateEmail)
                    .setParameter("uuid", candidateUuid)
                    .executeUpdate();
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");

            // Partner-track-only candidate — invisible to non-circle HR.
            P8ProfileFixtures.insertPosition(em, partnerPositionUuid, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertCandidate(em, partnerCandidateUuid,
                    "Pia", "Partner", "ACTIVE", null, null, adminUser);
            P8ProfileFixtures.insertOpenApplication(em, partnerApplicationUuid,
                    partnerCandidateUuid, partnerPositionUuid, "SCREENING");

            previousFlag = P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_pending_emails "
                            + "WHERE candidate_uuid IN (:candidates)")
                    .setParameter("candidates", List.of(candidateUuid, partnerCandidateUuid))
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_email_templates WHERE template_key = :key")
                    .setParameter("key", customTemplateKey).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid, partnerCandidateUuid),
                    List.of(positionUuid, partnerPositionUuid),
                    List.of(recruiterUser, teamleadUser, adminUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, INTERVIEWS_FLAG, previousFlag);
        });
    }

    // ---- helpers ---------------------------------------------------------------

    private String insertPendingRow(String candidate, String toEmail) {
        String uuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_pending_emails
                                    (uuid, candidate_uuid, template_key, reason, to_email, subject, body,
                                     status, created_at, updated_at, created_by)
                                VALUES (:uuid, :candidate, 'REJECTION_POST_INTERVIEW', 'REVIEW_FIRST_TEMPLATE',
                                        :to, 'Tak for samtalen', 'Kære kandidat', 'PENDING',
                                        NOW(), NOW(), 'test')
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("candidate", candidate)
                        .setParameter("to", toEmail)
                        .executeUpdate());
        return uuid;
    }

    private long mailCount() {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mail WHERE mail = :to")
                        .setParameter("to", candidateEmail)
                        .getSingleResult()).longValue());
    }

    // ---- Templates -------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void templateCrud_createUpdateList_asRecruiter() {
        String uuid = given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("templateKey", customTemplateKey, "name", "Testskabelon",
                        "subject", "Hej {{candidate_first_name}}", "body", "Brødtekst",
                        "autoSend", false, "active", true))
                .when().post("/recruitment/email-templates")
                .then().statusCode(201)
                .body("templateKey", equalTo(customTemplateKey))
                .body("trigger", equalTo(false))
                .extract().path("uuid");

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "Testskabelon v2", "subject", "Hej igen",
                        "body", "Ny brødtekst", "autoSend", true, "active", false))
                .when().put("/recruitment/email-templates/{uuid}", uuid)
                .then().statusCode(200)
                .body("name", equalTo("Testskabelon v2"))
                .body("autoSend", equalTo(true))
                .body("active", equalTo(false));

        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/email-templates")
                .then().statusCode(200)
                .body("templates.templateKey", hasItem("ACKNOWLEDGEMENT"))
                .body("templates.templateKey", hasItem(customTemplateKey))
                .body("totalCount", greaterThanOrEqualTo(4));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void templateValidation_duplicateKey409_badKey400_missingName400() {
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("templateKey", "ACKNOWLEDGEMENT", "name", "Dublet",
                        "subject", "s", "body", "b"))
                .when().post("/recruitment/email-templates")
                .then().statusCode(409);

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("templateKey", "lower-case-key!", "name", "Ugyldig",
                        "subject", "s", "body", "b"))
                .when().post("/recruitment/email-templates")
                .then().statusCode(409);

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("templateKey", customTemplateKey,
                        "subject", "s", "body", "b"))
                .when().post("/recruitment/email-templates")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void recruiterTierGate_teamleadGets404() {
        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/email-templates")
                .then().statusCode(404);

        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/emails/pending")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void flagOff_nonAdminScope_404() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "false"));

        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/email-templates")
                .then().statusCode(404);
    }

    // ---- Compose: render + manual send ---------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void render_mergesCandidateAndPositionFields() {
        String templateUuid = QuarkusTransaction.requiringNew().call(() ->
                (String) em.createNativeQuery("SELECT uuid FROM recruitment_email_templates "
                                + "WHERE template_key = 'ACKNOWLEDGEMENT'")
                        .getSingleResult());

        String subject = given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("templateUuid", templateUuid, "applicationUuid", applicationUuid))
                .when().post("/recruitment/candidates/{uuid}/emails/render", candidateUuid)
                .then().statusCode(200)
                .body("unresolvedFields", hasSize(0))
                .extract().path("subject");
        assertTrue(subject.contains("Løsningsarkitekt"),
                "position title must be merged into the subject");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void manualSend_persistsMailAndEmailSentEvent_dbAsserted() {
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("subject", "Opfølgning på din ansøgning",
                        "body", "Kære Søren\n\nVi vender tilbage i næste uge.",
                        "applicationUuid", applicationUuid))
                .when().post("/recruitment/candidates/{uuid}/emails/send", candidateUuid)
                .then().statusCode(201);

        assertEquals(1, mailCount());
        QuarkusTransaction.requiringNew().run(() -> {
            List<RecruitmentEvent> events = RecruitmentEvent.list(
                    "candidateUuid = ?1 and eventType = ?2",
                    candidateUuid, RecruitmentEventType.EMAIL_SENT);
            assertEquals(1, events.size());
            RecruitmentEvent event = events.get(0);
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
            assertEquals("USER", event.getActorType().name());
            assertEquals(recruiterUser, event.getActorUuid());
            assertTrue(event.getPayload().contains("\"trigger\":\"MANUAL\""));
            assertTrue(event.getPii().contains("Opfølgning"));
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void manualSend_candidateWithoutEmail_409() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET email = NULL WHERE uuid = :uuid")
                        .setParameter("uuid", candidateUuid).executeUpdate());

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("subject", "s", "body", "b"))
                .when().post("/recruitment/candidates/{uuid}/emails/send", candidateUuid)
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void manualSend_foreignApplicationContext_400() {
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("subject", "s", "body", "b",
                        "applicationUuid", partnerApplicationUuid))
                .when().post("/recruitment/candidates/{uuid}/emails/send", candidateUuid)
                .then().statusCode(400);
    }

    // ---- Review queue -----------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void approve_sendsAndLogsExactlyOnce_secondAttempt409() {
        String pendingUuid = insertPendingRow(candidateUuid, candidateEmail);

        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/emails/pending")
                .then().statusCode(200)
                .body("pending.uuid", hasItem(pendingUuid));

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("body", "Kære Søren\n\nRedigeret afslag med varme."))
                .when().post("/recruitment/emails/pending/{uuid}/approve", pendingUuid)
                .then().statusCode(200);

        assertEquals(1, mailCount());
        QuarkusTransaction.requiringNew().run(() -> {
            String status = (String) em.createNativeQuery(
                            "SELECT status FROM recruitment_pending_emails WHERE uuid = :uuid")
                    .setParameter("uuid", pendingUuid).getSingleResult();
            assertEquals("APPROVED", status);
            String mailBody = (String) em.createNativeQuery(
                            "SELECT content FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).getSingleResult();
            assertTrue(mailBody.contains("Redigeret afslag"),
                    "the recruiter's edited body must be what is sent");
            List<RecruitmentEvent> events = RecruitmentEvent.list(
                    "candidateUuid = ?1 and eventType = ?2",
                    candidateUuid, RecruitmentEventType.EMAIL_SENT);
            assertEquals(1, events.size());
            assertTrue(events.get(0).getPayload().contains("\"trigger\":\"REVIEW_APPROVED\""));
            assertEquals(recruiterUser, events.get(0).getActorUuid());
        });

        // One-shot: a second approve (or a stale retry) conflicts.
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .when().post("/recruitment/emails/pending/{uuid}/approve", pendingUuid)
                .then().statusCode(409);
        assertEquals(1, mailCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void dismiss_resolvesWithoutSending() {
        String pendingUuid = insertPendingRow(candidateUuid, candidateEmail);

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .when().post("/recruitment/emails/pending/{uuid}/dismiss", pendingUuid)
                .then().statusCode(200);

        assertEquals(0, mailCount());
        QuarkusTransaction.requiringNew().run(() -> {
            String status = (String) em.createNativeQuery(
                            "SELECT status FROM recruitment_pending_emails WHERE uuid = :uuid")
                    .setParameter("uuid", pendingUuid).getSingleResult();
            assertEquals("DISMISSED", status);
        });

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .when().post("/recruitment/emails/pending/{uuid}/dismiss", pendingUuid)
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerTrackQueueRow_hiddenFromNonCircleRecruiter_visibleToAdmin() {
        String pendingUuid = insertPendingRow(partnerCandidateUuid, "partner@example.com");

        // HR outside the circle: the row does not exist for them.
        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/emails/pending")
                .then().statusCode(200)
                .body("pending.uuid", not(hasItem(pendingUuid)));

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .when().post("/recruitment/emails/pending/{uuid}/approve", pendingUuid)
                .then().statusCode(404);

        // ADMIN sees everything (spec §7.2 matrix).
        given().header("X-Requested-By", adminUser)
                .when().get("/recruitment/emails/pending")
                .then().statusCode(200)
                .body("pending.uuid", hasItem(pendingUuid));
    }
}
