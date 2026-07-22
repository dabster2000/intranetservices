package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * P3 DoD (API): salary expectation is representable only as NOTE_ADDED with
 * {@code payload.field='SALARY_EXPECTATION'} and is gated by
 * {@code recruitment:comp} — an interviewer-scoped caller gets 403; a
 * comp-scoped caller succeeds. Regular notes need no comp scope.
 * <p>
 * The pipeline flag is flipped on for the duration of the class (the
 * endpoints 404 for non-admin callers while it is off — that path is
 * asserted too).
 */
@QuarkusTest
class CandidateNoteCompScopeApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";

    @Inject
    EntityManager em;

    private String candidateUuid;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        candidateUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, status, created_by_useruuid,
                                 created_at, updated_at)
                            VALUES (:uuid, 'Comp', 'Fixture', 'ACTIVE', :actor, NOW(), NOW())
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
    @TestSecurity(user = "interviewer-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void salaryNote_withoutCompScope_is403() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", UUID.randomUUID().toString())
                .body("""
                        {"text": "expects 75.000", "isPrivate": true, "field": "SALARY_EXPECTATION"}
                        """)
                .when()
                .post("/recruitment/candidates/{uuid}/notes", candidateUuid)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "recruiter-client", roles = {"recruitment:read", "recruitment:write", "recruitment:comp"})
    void salaryNote_withCompScope_isCreated() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", UUID.randomUUID().toString())
                .body("""
                        {"text": "expects 75.000", "isPrivate": true, "field": "SALARY_EXPECTATION"}
                        """)
                .when()
                .post("/recruitment/candidates/{uuid}/notes", candidateUuid)
                .then()
                .statusCode(201);
    }

    @Test
    @TestSecurity(user = "interviewer-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void regularNote_needsNoCompScope() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", UUID.randomUUID().toString())
                .body("""
                        {"text": "great first call", "isPrivate": false}
                        """)
                .when()
                .post("/recruitment/candidates/{uuid}/notes", candidateUuid)
                .then()
                .statusCode(201);
    }

    @Test
    @TestSecurity(user = "no-write-client", roles = {"recruitment:read"})
    void note_withoutWriteScope_is403() {
        given()
                .contentType("application/json")
                .header("X-Requested-By", UUID.randomUUID().toString())
                .body("""
                        {"text": "should not land", "isPrivate": false}
                        """)
                .when()
                .post("/recruitment/candidates/{uuid}/notes", candidateUuid)
                .then()
                .statusCode(403);
    }
}
