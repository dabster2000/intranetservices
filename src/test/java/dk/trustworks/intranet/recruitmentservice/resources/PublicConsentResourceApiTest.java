package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST contract of the P19 public surface {@code /consent/{token}}.
 * Deliberately NO {@code @TestSecurity} — every request is anonymous,
 * proving {@code @PermitAll} end-to-end (the P5 pattern).
 * <ul>
 *   <li>uniform, byte-identical 404 for a malformed token, an unknown
 *       token, an expired token, an anonymized candidate and the disabled
 *       flag — an attacker learns nothing (plan §P19 DoD);</li>
 *   <li>GRANT sets the consent GRANTED for 12 months and pushes the
 *       candidate's retention deadline to match — asserted against the
 *       DATABASE, never the response body (the P11 flush lesson);</li>
 *   <li>WITHDRAW resumes the 6-month process countdown;</li>
 *   <li>an unknown action answers a code-only 400.</li>
 * </ul>
 * Every POST carries a unique {@code X-Forwarded-For} so the per-IP rate
 * limiter (which covers {@code /consent/*} since P19) never throttles the
 * suite.
 */
@QuarkusTest
class PublicConsentResourceApiTest {

    private static final String FLAG = "recruitment.gdpr.enabled";
    private static final String NOT_FOUND_BODY = "{\"error\":\"NOT_FOUND\"}";
    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    @Inject
    EntityManager em;

    private String candidateUuid;
    private String consentUuid;
    private String rawToken;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        candidateUuid = UUID.randomUUID().toString();
        consentUuid = UUID.randomUUID().toString();
        rawToken = "t".repeat(43 - 8) + UUID.randomUUID().toString().substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, email, status, pool_status, source,
                                 process_ended_at, retention_deadline,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, 'Consent', 'Fixture', :email, 'POOLED', 'PROSPECT',
                                    'OTHER', :ended, :deadline, :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("email", "p19.consent+" + candidateUuid.substring(0, 8)
                            + "@example.invalid")
                    .setParameter("ended", utcNow().minusDays(30))
                    .setParameter("deadline", utcNow().plusDays(20))
                    .setParameter("actor", UUID.randomUUID().toString())
                    .executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_consents
                                (uuid, candidate_uuid, kind, status, token_hash, token_expires_at,
                                 created_at, updated_at, created_by)
                            VALUES (:uuid, :candidate, 'TALENT_POOL_RETENTION', 'REQUESTED',
                                    :hash, :expires, NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", consentUuid)
                    .setParameter("candidate", candidateUuid)
                    .setParameter("hash", sha256Hex(rawToken))
                    .setParameter("expires", utcNow().plusDays(20))
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
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            restoreFlag();
        });
    }

    // ---- Uniform failure ---------------------------------------------------------

    @Test
    void malformedToken_unknownToken_expiredToken_flagOff_allAnswerTheSame404() {
        // Malformed (wrong length / charset).
        expectUniform404(get("not-a-token"));
        expectUniform404(get("x".repeat(200)));
        // Unknown but well-formed.
        expectUniform404(get("u".repeat(43)));
        // Expired token.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_consents SET token_expires_at = :past "
                                + "WHERE uuid = :u")
                        .setParameter("past", utcNow().minusDays(1))
                        .setParameter("u", consentUuid).executeUpdate());
        expectUniform404(get(rawToken));
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_consents SET token_expires_at = :future "
                                + "WHERE uuid = :u")
                        .setParameter("future", utcNow().plusDays(20))
                        .setParameter("u", consentUuid).executeUpdate());
        // Anonymized candidate.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET status = 'ANONYMIZED' "
                                + "WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate());
        expectUniform404(get(rawToken));
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET status = 'POOLED' "
                                + "WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate());
        // Flag off — even a VALID token answers the same 404.
        QuarkusTransaction.requiringNew().run(() -> setFlag("false"));
        expectUniform404(get(rawToken));
        // POST is equally uniform.
        expectUniform404(post(rawToken, "GRANT"));
    }

    private io.restassured.response.Response get(String token) {
        return given()
                .header("X-Forwarded-For", nextIp())
                .when().get("/consent/{token}", token);
    }

    private io.restassured.response.Response post(String token, String action) {
        return given()
                .header("X-Forwarded-For", nextIp())
                .contentType("application/json")
                .body("{\"action\":\"" + action + "\"}")
                .when().post("/consent/{token}", token);
    }

    private static void expectUniform404(io.restassured.response.Response response) {
        assertEquals(404, response.statusCode());
        assertEquals(NOT_FOUND_BODY, response.getBody().asString(),
                "every failure must be byte-identical — nothing may leak");
    }

    // ---- Happy paths ----------------------------------------------------------------

    @Test
    void validToken_rendersFirstNameAndState_only() {
        get(rawToken).then()
                .statusCode(200)
                .body("firstName", equalTo("Consent"))
                .body("kind", equalTo("TALENT_POOL_RETENTION"))
                .body("status", equalTo("REQUESTED"));
    }

    @Test
    void grant_setsTwelveMonths_dbAsserted_andAppendsTheEvent() {
        post(rawToken, "GRANT").then()
                .statusCode(200)
                .body("status", equalTo("GRANTED"));

        QuarkusTransaction.requiringNew().run(() -> {
            Object deadline = em.createNativeQuery(
                            "SELECT retention_deadline FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertNotNull(deadline);
            LocalDateTime asDate = (LocalDateTime) deadline;
            long days = ChronoUnit.DAYS.between(utcNow(), asDate);
            assertTrue(days > 360 && days < 370,
                    "grant must push retention_deadline ~12 months out, was +" + days + " days");
            Object status = em.createNativeQuery(
                            "SELECT status FROM recruitment_consents WHERE uuid = :u")
                    .setParameter("u", consentUuid).getSingleResult();
            assertEquals("GRANTED", status);
            Number events = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM recruitment_events WHERE candidate_uuid = :c "
                                    + "AND event_type = 'CONSENT_GRANTED'")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertEquals(1L, events.longValue());
        });
    }

    @Test
    void withdraw_resumesTheProcessCountdown_dbAsserted() {
        post(rawToken, "GRANT").then().statusCode(200);
        post(rawToken, "WITHDRAW").then()
                .statusCode(200)
                .body("status", equalTo("WITHDRAWN"));

        QuarkusTransaction.requiringNew().run(() -> {
            Object deadline = em.createNativeQuery(
                            "SELECT retention_deadline FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).getSingleResult();
            assertNotNull(deadline, "an ended process resumes its 6-month countdown");
            LocalDateTime asDate = (LocalDateTime) deadline;
            long days = ChronoUnit.DAYS.between(utcNow(), asDate);
            assertTrue(days > 140 && days < 160,
                    "process ended 30 days ago ⇒ deadline ≈ +5 months, was +" + days + " days");
        });
    }

    @Test
    void unknownAction_answersCodeOnly400() {
        post(rawToken, "DELETE_EVERYTHING").then()
                .statusCode(400)
                .body("error", equalTo("INVALID_ACTION"));
    }

    // ---- Helpers ----------------------------------------------------------------------

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String nextIp() {
        return "10.19.0." + IP_COUNTER.incrementAndGet() + ", 192.0.2.1";
    }

    private static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
