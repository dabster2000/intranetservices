package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * REST contract of the P19 DPO surface {@code /recruitment/gdpr/*}:
 * <ul>
 *   <li>the queue endpoint's shape (KPIs + three queues + log);</li>
 *   <li>Art. 14 send — email mode queues the mail and appends BOTH events
 *       (DB-asserted, the P11 flush lesson); manual mode appends only the
 *       compliance fact; a second send conflicts (409); a candidate
 *       without an email conflicts with guidance;</li>
 *   <li>DSAR record → open queue item; duplicate 409; export streams a
 *       real ZIP (magic bytes) and closes the item;</li>
 *   <li>anonymize demands the typed full-name confirmation server-side
 *       (400 on mismatch), then answers the per-target summary;</li>
 *   <li>the whole surface 404s for a non-admin caller while
 *       {@code recruitment.gdpr.enabled} is off.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentGdprResourceApiTest {

    private static final String FLAG = "recruitment.gdpr.enabled";
    private static final String SCOPES = "recruitment:gdpr";

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private String candidateUuid;
    private String candidateEmail;
    private final String dpoUser = UUID.randomUUID().toString();
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        when(storageService.deleteAllCandidateFiles(any(UUID.class))).thenReturn(0);
        when(storageService.listCandidateFiles(any(UUID.class))).thenReturn(List.of());
        candidateUuid = UUID.randomUUID().toString();
        candidateEmail = "p19.dpo+" + candidateUuid.substring(0, 8) + "@example.invalid";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, email, status, source,
                                 art14_required, art14_deadline,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, 'Dpo', 'Fixture', :email, 'ACTIVE', 'REFERRAL',
                                    1, :deadline, :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("email", candidateEmail)
                    .setParameter("deadline", LocalDateTime.now(ZoneOffset.UTC).plusDays(3))
                    .setParameter("actor", UUID.randomUUID().toString())
                    .executeUpdate();
            setFlag("true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            restoreFlag();
        });
    }

    // ---- Gate ---------------------------------------------------------------------

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void flagOff_nonAdminCaller_getsUniform404() {
        QuarkusTransaction.requiringNew().run(() -> setFlag("false"));
        dpo().when().get("/recruitment/gdpr/queue").then().statusCode(404);
        dpo().body("{}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/dsar", candidateUuid)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "unscoped-client", roles = {"recruitment:read"})
    void withoutTheGdprScope_everythingIs403() {
        dpo().when().get("/recruitment/gdpr/queue").then().statusCode(403);
    }

    // ---- Queue ---------------------------------------------------------------------

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void queue_hasKpisAndTheFixtureInTheArt14Section() {
        dpo().when().get("/recruitment/gdpr/queue").then()
                .statusCode(200)
                .body("engineEnabled", equalTo(true))
                .body("kpis.art14DueCount", greaterThanOrEqualTo(1))
                .body("art14Due.findAll { it.candidateUuid == '" + candidateUuid + "' }.size()",
                        equalTo(1));
    }

    // ---- Art. 14 -------------------------------------------------------------------

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void art14Send_emailMode_queuesMailAndAppendsBothEvents_dbAsserted() {
        dpo().body("{\"manual\":false}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/art14-send", candidateUuid)
                .then().statusCode(200)
                .body("art14NoticeSent", equalTo(true));

        QuarkusTransaction.requiringNew().run(() -> {
            Number mails = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).getSingleResult();
            assertEquals(1L, mails.longValue(), "the notice email must be in the outbox");
            Number notice = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM recruitment_events WHERE candidate_uuid = :c "
                                    + "AND event_type = 'ART14_NOTICE_SENT'")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertEquals(1L, notice.longValue());
            Number sent = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM recruitment_events WHERE candidate_uuid = :c "
                                    + "AND event_type = 'EMAIL_SENT'")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertEquals(1L, sent.longValue());
        });

        // Second send: 409 — the notice is a one-time duty.
        dpo().body("{\"manual\":false}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/art14-send", candidateUuid)
                .then().statusCode(409);
        // And the queue no longer lists the candidate.
        dpo().when().get("/recruitment/gdpr/queue").then()
                .statusCode(200)
                .body("art14Due.findAll { it.candidateUuid == '" + candidateUuid + "' }.size()",
                        equalTo(0));
    }

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void art14Send_manualMode_appendsOnlyTheComplianceFact() {
        dpo().body("{\"manual\":true,\"note\":\"notified via LinkedIn\"}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/art14-send", candidateUuid)
                .then().statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            Object payload = em.createNativeQuery(
                            "SELECT payload FROM recruitment_events WHERE candidate_uuid = :c "
                                    + "AND event_type = 'ART14_NOTICE_SENT'")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertTrue(payload.toString().contains("\"channel\":\"MANUAL\""));
            Number mails = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).getSingleResult();
            assertEquals(0L, mails.longValue(), "manual mode must not send anything");
        });
    }

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void art14Send_withoutEmail_conflictsWithGuidance() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET email = NULL "
                                + "WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate());
        dpo().body("{\"manual\":false}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/art14-send", candidateUuid)
                .then().statusCode(409);
    }

    // ---- DSAR ----------------------------------------------------------------------

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void dsar_recordThenExport_opensAndClosesTheQueueItem() {
        dpo().body("{\"note\":\"came in via email\"}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/dsar", candidateUuid)
                .then().statusCode(200)
                .body("openDsar", equalTo(true));

        // Duplicate while open: 409.
        dpo().body("{}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/dsar", candidateUuid)
                .then().statusCode(409);

        dpo().when().get("/recruitment/gdpr/queue").then()
                .statusCode(200)
                .body("openDsars.findAll { it.candidateUuid == '" + candidateUuid + "' }.size()",
                        equalTo(1));

        byte[] zip = dpo().when()
                .post("/recruitment/gdpr/candidates/{uuid}/dsar-export", candidateUuid)
                .then().statusCode(200)
                .contentType("application/zip")
                .extract().asByteArray();
        assertTrue(zip.length > 4 && zip[0] == 0x50 && zip[1] == 0x4b,
                "the export must be a real ZIP (PK magic bytes)");

        dpo().when().get("/recruitment/gdpr/queue").then()
                .statusCode(200)
                .body("openDsars.findAll { it.candidateUuid == '" + candidateUuid + "' }.size()",
                        equalTo(0));
    }

    // ---- Anonymize -------------------------------------------------------------------

    @Test
    @TestSecurity(user = "dpo-client", roles = {SCOPES})
    void anonymize_demandsTheTypedFullName_serverSide() {
        dpo().body("{\"confirmText\":\"wrong name\"}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/anonymize", candidateUuid)
                .then().statusCode(400)
                .body("error", equalTo("CONFIRMATION_MISMATCH"));

        dpo().body("{\"confirmText\":\"Dpo Fixture\"}").when()
                .post("/recruitment/gdpr/candidates/{uuid}/anonymize", candidateUuid)
                .then().statusCode(200)
                .body("mode", equalTo("ON_REQUEST"))
                .body("alreadyAnonymized", equalTo(false));

        QuarkusTransaction.requiringNew().run(() -> {
            Object status = em.createNativeQuery(
                            "SELECT status FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertEquals("ANONYMIZED", status, "asserted against the DB, not the response");
        });
    }

    // ---- Helpers -----------------------------------------------------------------------

    private io.restassured.specification.RequestSpecification dpo() {
        return given()
                .contentType("application/json")
                .header("X-Requested-By", dpoUser);
    }

    private void setFlag(String value) {
        List<?> current = em.createNativeQuery(
                        "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                .setParameter("key", FLAG).getResultList();
        if (previousFlagValue == null && !current.isEmpty()) {
            previousFlagValue = (String) current.get(0);
        }
        if (current.isEmpty()) {
            em.createNativeQuery("""
                            INSERT INTO app_settings (setting_key, setting_value, category)
                            VALUES (:key, :value, 'recruitment')
                            """)
                    .setParameter("key", FLAG).setParameter("value", value).executeUpdate();
        } else {
            em.createNativeQuery(
                            "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                    .setParameter("key", FLAG).setParameter("value", value).executeUpdate();
        }
    }

    private void restoreFlag() {
        if (previousFlagValue == null) {
            em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", FLAG).executeUpdate();
        } else {
            em.createNativeQuery(
                            "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                    .setParameter("key", FLAG)
                    .setParameter("value", previousFlagValue).executeUpdate();
        }
        previousFlagValue = null;
    }
}
