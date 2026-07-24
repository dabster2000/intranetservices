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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Manual candidate-document upload
 * ({@code POST /recruitment/candidates/{uuid}/documents}): happy path
 * stores via S3 and appends {@code DOCUMENT_UPLOADED} with
 * {@code origin='manual'} (raw filename in pii only); the shared
 * PDF/JPEG/PNG magic-byte guard and the kind allowlist reject everything
 * else; write scope required. Storage is mocked — no S3 in tests.
 */
@QuarkusTest
class CandidateDocumentUploadApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";
    private static final byte[] PDF_BYTES = "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8);

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private String candidateUuid;
    private String viewerUuid;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        candidateUuid = UUID.randomUUID().toString();
        viewerUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, viewerUuid, "Rikke", "Recruiter");
            P8ProfileFixtures.insertRole(em, viewerUuid, "HR");
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, status, created_by_useruuid,
                                 created_at, updated_at)
                            VALUES (:uuid, 'Doc', 'Fixture', 'ACTIVE', :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("actor", UUID.randomUUID().toString())
                    .executeUpdate();
            List<?> current = em.createNativeQuery(
                            "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", FLAG)
                    .getResultList();
            previousFlagValue = current.isEmpty() ? null : (String) current.get(0);
            if (previousFlagValue == null) {
                em.createNativeQuery("""
                                INSERT INTO app_settings (setting_key, setting_value, category)
                                VALUES (:key, 'true', 'recruitment')
                                """)
                        .setParameter("key", FLAG)
                        .executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = 'true' WHERE setting_key = :key")
                        .setParameter("key", FLAG)
                        .executeUpdate();
            }
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :uuid")
                    .setParameter("uuid", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :uuid")
                    .setParameter("uuid", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid = :u")
                    .setParameter("u", viewerUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid = :u")
                    .setParameter("u", viewerUuid).executeUpdate();
            if (previousFlagValue == null) {
                em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", previousFlagValue)
                        .setParameter("key", FLAG)
                        .executeUpdate();
            }
        });
    }

    @Test
    @TestSecurity(user = "recruiter-client", roles = {"recruitment:read", "recruitment:write"})
    void pdfUpload_storesAndAppendsManualDocumentUploadedEvent() {
        when(storageService.storeApplicationDocument(any(), anyString(), any()))
                .thenReturn("file-uuid-1");

        given()
                .header("X-Requested-By", viewerUuid)
                .multiPart("kind", "CV")
                .multiPart("file", "Jens Hansen CV.pdf", PDF_BYTES, "application/pdf")
                .when()
                .post("/recruitment/candidates/{uuid}/documents", candidateUuid)
                .then()
                .statusCode(201)
                .body("fileUuid", equalTo("file-uuid-1"));

        List<?> rows = QuarkusTransaction.requiringNew().call(() ->
                em.createNativeQuery("""
                                SELECT payload, pii FROM recruitment_events
                                WHERE candidate_uuid = :uuid AND event_type = 'DOCUMENT_UPLOADED'
                                """)
                        .setParameter("uuid", candidateUuid)
                        .getResultList());
        assertEquals(1, rows.size(), "exactly one DOCUMENT_UPLOADED event");
        Object[] row = (Object[]) rows.get(0);
        String payload = String.valueOf(row[0]);
        String pii = String.valueOf(row[1]);
        assertTrue(payload.contains("\"origin\":\"manual\""), "origin=manual in payload");
        assertTrue(payload.contains("\"kind\":\"CV\""), "kind in payload");
        assertTrue(payload.contains("file-uuid-1"), "file uuid in payload");
        assertTrue(!payload.contains("Jens Hansen"), "raw filename never in payload");
        assertTrue(pii.contains("Jens Hansen CV.pdf"), "raw filename lives in pii");
    }

    @Test
    @TestSecurity(user = "recruiter-client", roles = {"recruitment:read", "recruitment:write"})
    void unknownKind_is400() {
        given()
                .header("X-Requested-By", viewerUuid)
                .multiPart("kind", "PASSPORT")
                .multiPart("file", "cv.pdf", PDF_BYTES, "application/pdf")
                .when()
                .post("/recruitment/candidates/{uuid}/documents", candidateUuid)
                .then()
                .statusCode(400);
        verifyNoInteractions(storageService);
    }

    @Test
    @TestSecurity(user = "recruiter-client", roles = {"recruitment:read", "recruitment:write"})
    void magicByteMismatch_is415() {
        given()
                .header("X-Requested-By", viewerUuid)
                .multiPart("kind", "OTHER")
                .multiPart("file", "cv.pdf",
                        "plain text pretending".getBytes(StandardCharsets.UTF_8),
                        "application/pdf")
                .when()
                .post("/recruitment/candidates/{uuid}/documents", candidateUuid)
                .then()
                .statusCode(415);
        verifyNoInteractions(storageService);
    }

    @Test
    @TestSecurity(user = "read-only-client", roles = {"recruitment:read"})
    void withoutWriteScope_is403() {
        given()
                .header("X-Requested-By", viewerUuid)
                .multiPart("kind", "CV")
                .multiPart("file", "cv.pdf", PDF_BYTES, "application/pdf")
                .when()
                .post("/recruitment/candidates/{uuid}/documents", candidateUuid)
                .then()
                .statusCode(403);
        verifyNoInteractions(storageService);
    }
}
