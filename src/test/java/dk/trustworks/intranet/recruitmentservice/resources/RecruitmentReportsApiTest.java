package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingProjector;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * REST contract of the P20 reports surface {@code /recruitment/reports}:
 * <ul>
 *   <li>the flag gate (404 while {@code recruitment.gdpr.enabled} is off
 *       for non-admin clients; {@code admin:*} bypasses);</li>
 *   <li>range validation (defaults to the fiscal year; YYYY-MM only;
 *       from ≤ to; bounded window);</li>
 *   <li>rebuild is {@code recruitment:admin} only;</li>
 *   <li><b>partner-track leakage (DoD):</b> with a partner position and a
 *       CIRCLE-visibility funnel in the stream, no report surface carries
 *       the position's title, its uuid, or the candidate's name.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentReportsApiTest {

    private static final String FLAG = "recruitment.gdpr.enabled";
    private static final String PARTNER_TITLE = "P20_PARTNER_TITLE_SENTINEL";
    private static final String CANDIDATE_NAME = "P20_PartnerCandidate_Sentinel";

    @Inject
    EntityManager em;

    @Inject
    RecruitmentReportingProjector projector;

    private String partnerPositionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private final String caller = UUID.randomUUID().toString();
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        partnerPositionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            setFlag("true");
            em.createNativeQuery("""
                            INSERT INTO recruitment_positions
                                (uuid, title, hiring_track, stage_set, demand_rag, status,
                                 opened_at, created_at, updated_at, created_by)
                            VALUES (:uuid, :title, 'PARTNER',
                                    '["SCREENING","INTERVIEW_1","OFFER","HIRED"]', 'GREEN', 'OPEN',
                                    NOW(), NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", partnerPositionUuid)
                    .setParameter("title", PARTNER_TITLE)
                    .executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, email, status, source,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, :first, 'Sentinel', :email, 'ACTIVE', 'LINKEDIN_SEARCH',
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("first", CANDIDATE_NAME)
                    .setParameter("email", "p20.leak." + candidateUuid.substring(0, 8) + "@example.invalid")
                    .setParameter("actor", caller)
                    .executeUpdate();
            insertPartnerEvent("CANDIDATE_CREATED", null,
                    "{\"source\":\"LINKEDIN_SEARCH\"}", LocalDateTime.of(2026, 4, 1, 10, 0));
            insertPartnerEvent("APPLICATION_CREATED", applicationUuid,
                    "{\"hiring_track\":\"PARTNER\",\"initial_stage\":\"SCREENING\",\"origin\":\"manual\"}",
                    LocalDateTime.of(2026, 4, 2, 10, 0));
            insertPartnerEvent("APPLICATION_STAGE_CHANGED", applicationUuid,
                    "{\"from\":\"SCREENING\",\"to\":\"INTERVIEW_1\",\"direction\":\"FORWARD\"}",
                    LocalDateTime.of(2026, 4, 9, 10, 0));
        });
        projector.rebuild();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid = :p")
                    .setParameter("p", partnerPositionUuid).executeUpdate();
            restoreFlag();
        });
        projector.rebuild();
    }

    // ---- Gate ---------------------------------------------------------------------

    @Test
    @TestSecurity(user = "reader", roles = {"recruitment:read"})
    void flagOff_nonAdminCaller_getsUniform404() {
        QuarkusTransaction.requiringNew().run(() -> setFlag("false"));
        caller().when().get("/recruitment/reports").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin-client", roles = {"recruitment:read", "admin:*"})
    void flagOff_adminCaller_bypasses() {
        QuarkusTransaction.requiringNew().run(() -> setFlag("false"));
        caller().when().get("/recruitment/reports").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "unscoped", roles = {"users:read"})
    void withoutTheReadScope_403() {
        caller().when().get("/recruitment/reports").then().statusCode(403);
    }

    // ---- Range validation -----------------------------------------------------------

    @Test
    @TestSecurity(user = "reader", roles = {"recruitment:read"})
    void defaultRange_isTheCurrentFiscalYear_julyToJune() {
        var response = caller().when().get("/recruitment/reports").then()
                .statusCode(200)
                .body("from", notNullValue())
                .body("to", notNullValue())
                .extract();
        String from = response.path("from");
        String to = response.path("to");
        org.junit.jupiter.api.Assertions.assertTrue(from.endsWith("-07"),
                "fiscal year starts in July, got " + from);
        org.junit.jupiter.api.Assertions.assertTrue(to.endsWith("-06"),
                "fiscal year ends in June, got " + to);
    }

    @Test
    @TestSecurity(user = "reader", roles = {"recruitment:read"})
    void rangeValidation_rejectsBadInput() {
        caller().when().get("/recruitment/reports?from=2026-01").then().statusCode(400);
        caller().when().get("/recruitment/reports?from=januar&to=2026-06").then().statusCode(400);
        caller().when().get("/recruitment/reports?from=2026-06&to=2026-01").then().statusCode(400);
        caller().when().get("/recruitment/reports?from=2020-01&to=2026-06").then().statusCode(400);
    }

    // ---- Rebuild authorization --------------------------------------------------------

    @Test
    @TestSecurity(user = "reader", roles = {"recruitment:read"})
    void rebuild_requiresTheAdminScope() {
        caller().when().post("/recruitment/reports/rebuild").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"recruitment:admin"})
    void rebuild_answersASummary() {
        caller().when().post("/recruitment/reports/rebuild").then()
                .statusCode(200)
                .body("blocked", equalTo(false));
    }

    // ---- Partner-track leakage (DoD) ---------------------------------------------------

    @Test
    @TestSecurity(user = "reader", roles = {"recruitment:read"})
    void noReportSurface_exposesPartnerPositionsOrCandidates() {
        String body = caller()
                .when().get("/recruitment/reports?from=2026-04&to=2026-04")
                .then().statusCode(200)
                .extract().asString();
        assertFalse(body.contains(PARTNER_TITLE), "partner position title leaked into the reports");
        assertFalse(body.contains(partnerPositionUuid), "partner position uuid leaked into the reports");
        assertFalse(body.contains(CANDIDATE_NAME), "candidate name leaked into the reports");
        assertFalse(body.contains(candidateUuid), "candidate uuid leaked into the reports");
        // ... while the partner activity IS present in the aggregates:
        caller().when().get("/recruitment/reports?from=2026-04&to=2026-04").then()
                .body("funnel.findAll { it.stageFrom == 'SCREENING' && it.stageTo == 'INTERVIEW_1' }.size()",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1));
    }

    // ---- Helpers -----------------------------------------------------------------------

    private io.restassured.specification.RequestSpecification caller() {
        return given().contentType("application/json").header("X-Requested-By", caller);
    }

    private void insertPartnerEvent(String type, String application, String payload, LocalDateTime occurredAt) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_events
                            (event_id, event_type, candidate_uuid, application_uuid, position_uuid,
                             actor_uuid, actor_type, occurred_at, visibility, payload, pii, pii_state)
                        VALUES (:id, :type, :candidate, :application, :position,
                                :actor, 'USER', :occurredAt, 'CIRCLE', :payload, NULL, 'NONE')
                        """)
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("type", type)
                .setParameter("candidate", candidateUuid)
                .setParameter("application", application)
                .setParameter("position", "APPLICATION_CREATED".equals(type)
                        || "APPLICATION_STAGE_CHANGED".equals(type) ? partnerPositionUuid : null)
                .setParameter("actor", caller)
                .setParameter("occurredAt", occurredAt)
                .setParameter("payload", payload)
                .executeUpdate();
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
