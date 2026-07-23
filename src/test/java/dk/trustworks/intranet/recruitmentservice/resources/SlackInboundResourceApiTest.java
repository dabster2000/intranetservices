package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P13 §DoD — the inbound dispatch pipeline end-to-end against the real
 * local DB: master gate, fail-closed actor resolution, atomic dedupe,
 * allowlist drop, envelope validation, internal-header existence-hiding
 * and the TTL prune. Every assertion that matters is made against the
 * DATABASE in a fresh transaction, never against response bodies alone
 * (the findings §P11 flush lesson).
 */
@QuarkusTest
class SlackInboundResourceApiTest {

    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";
    private static final String INBOUND_PATH = "/recruitment/slack/inbound";
    private static final String SOURCE_HEADER = "X-Slack-Inbound-Source";

    @Inject
    EntityManager em;

    private String linkedUserUuid;
    private String terminatedUserUuid;
    private String linkedSlackId;
    private String terminatedSlackId;
    private String previousMaster;
    private long eventCountBefore;

    @BeforeEach
    void seed() {
        linkedUserUuid = UUID.randomUUID().toString();
        terminatedUserUuid = UUID.randomUUID().toString();
        linkedSlackId = "U" + UUID.randomUUID().toString().substring(0, 10).replace("-", "");
        terminatedSlackId = "U" + UUID.randomUUID().toString().substring(0, 10).replace("-", "");

        QuarkusTransaction.requiringNew().run(() -> {
            previousMaster = P8ProfileFixtures.setFlag(em, MASTER_FLAG, "true");

            P8ProfileFixtures.insertUser(em, linkedUserUuid, "Slack", "Linked");
            linkSlack(linkedUserUuid, linkedSlackId);
            insertStatus(linkedUserUuid, "ACTIVE");

            P8ProfileFixtures.insertUser(em, terminatedUserUuid, "Slack", "Gone");
            linkSlack(terminatedUserUuid, terminatedSlackId);
            insertStatus(terminatedUserUuid, "TERMINATED");

            eventCountBefore = eventCount();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.restoreFlag(em, MASTER_FLAG, previousMaster);
            em.createNativeQuery("DELETE FROM recruitment_slack_inbound_dedupe WHERE slack_team_id = 'T-p13test'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM userstatus WHERE useruuid IN (:a, :b)")
                    .setParameter("a", linkedUserUuid).setParameter("b", terminatedUserUuid)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN (:a, :b)")
                    .setParameter("a", linkedUserUuid).setParameter("b", terminatedUserUuid)
                    .executeUpdate();
        });
    }

    // ---- Master gate -----------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void masterGateOff_isInert_noClaimNoEvents() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, MASTER_FLAG, "false"));

        String payloadId = uniquePayloadId();
        postInbound(envelope(payloadId, linkedSlackId, "some_action"))
                .then().statusCode(200)
                .body("disposition", equalTo("DISABLED"))
                .body("ephemeralText", equalTo("This feature is currently disabled."));

        assertNoClaim(payloadId);
        assertNoNewEvents();
    }

    // ---- Actor resolution (fail-closed) -----------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void unknownSlackUser_failsClosed_zeroSideEffects() {
        String payloadId = uniquePayloadId();
        postInbound(envelope(payloadId, "UNOBODY0000", "some_action"))
                .then().statusCode(200)
                .body("disposition", equalTo("NOT_LINKED"))
                .body("ephemeralText", equalTo("Your Slack account isn't linked to an intranet user"));

        assertNoClaim(payloadId);
        assertNoNewEvents();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void terminatedUser_failsClosed_zeroSideEffects() {
        String payloadId = uniquePayloadId();
        postInbound(envelope(payloadId, terminatedSlackId, "some_action"))
                .then().statusCode(200)
                .body("disposition", equalTo("NOT_LINKED"));

        assertNoClaim(payloadId);
        assertNoNewEvents();
    }

    // ---- Dedupe + allowlist ------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void unknownHandlerKey_isLoggedAndDropped_claimPersists() {
        String payloadId = uniquePayloadId();
        postInbound(envelope(payloadId, linkedSlackId, "action_that_does_not_exist"))
                .then().statusCode(200)
                .body("disposition", equalTo("UNKNOWN"))
                .body("ephemeralText", nullValue());

        // The claim row IS the side effect — asserted in a fresh tx.
        assertEquals(1, claimCount(payloadId), "dispatch must claim the payload id durably");
        assertNoNewEvents();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void samePayloadIdTwice_secondIsDuplicate_singleClaimRow() {
        String payloadId = uniquePayloadId();
        postInbound(envelope(payloadId, linkedSlackId, "some_action"))
                .then().statusCode(200)
                .body("disposition", equalTo("UNKNOWN"));
        postInbound(envelope(payloadId, linkedSlackId, "some_action"))
                .then().statusCode(200)
                .body("disposition", equalTo("DUPLICATE"));

        assertEquals(1, claimCount(payloadId), "a Slack retry must never claim twice");
        assertNoNewEvents();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void expiredClaims_arePrunedOnDispatch() {
        String stalePayloadId = uniquePayloadId();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_slack_inbound_dedupe
                                    (payload_key, slack_team_id, received_at)
                                VALUES (:key, 'T-p13test', NOW(3) - INTERVAL 25 HOUR)
                                """)
                        .setParameter("key", "interactions:" + stalePayloadId)
                        .executeUpdate());

        postInbound(envelope(uniquePayloadId(), linkedSlackId, "some_action"))
                .then().statusCode(200);

        assertEquals(0, claimCount(stalePayloadId), "claims older than the TTL must be pruned");
    }

    // ---- Envelope validation + caller contract -----------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void missingInternalHeader_is404_existenceHiding() {
        given().contentType(ContentType.JSON)
                .body(envelope(uniquePayloadId(), linkedSlackId, "some_action"))
                .post(INBOUND_PATH)
                .then().statusCode(404);
        assertNoNewEvents();
    }

    @Test
    @TestSecurity(user = "read-only-client", roles = {"recruitment:read"})
    void readScope_cannotDispatch() {
        postInbound(envelope(uniquePayloadId(), linkedSlackId, "some_action"))
                .then().statusCode(403);
        assertNoNewEvents();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void invalidSurface_is400() {
        Map<String, Object> bad = new java.util.HashMap<>(envelope(uniquePayloadId(), linkedSlackId, "some_action"));
        bad.put("surface", "webhooks");
        postInbound(bad).then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void blankSlackUser_is400() {
        Map<String, Object> bad = new java.util.HashMap<>(envelope(uniquePayloadId(), "", "some_action"));
        postInbound(bad).then().statusCode(400);
    }

    // ---- Helpers -----------------------------------------------------------------

    private static Map<String, Object> envelope(String payloadId, String slackUserId, String handlerKey) {
        return Map.of(
                "surface", "interactions",
                "payloadId", payloadId,
                "slackUserId", slackUserId,
                "slackTeamId", "T-p13test",
                "kind", "block_actions",
                "handlerKey", handlerKey);
    }

    private static io.restassured.response.Response postInbound(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .header(SOURCE_HEADER, "bff")
                .body(body)
                .post(INBOUND_PATH);
    }

    private static String uniquePayloadId() {
        return "trg-" + UUID.randomUUID();
    }

    private void linkSlack(String userUuid, String slackId) {
        em.createNativeQuery("UPDATE user SET slackusername = :slack WHERE uuid = :uuid")
                .setParameter("slack", slackId)
                .setParameter("uuid", userUuid)
                .executeUpdate();
    }

    private void insertStatus(String userUuid, String status) {
        em.createNativeQuery("""
                        INSERT INTO userstatus (uuid, useruuid, companyuuid, status, allocation, statusdate, type,
                                                is_tw_bonus_eligible, created_at, updated_at, created_by)
                        VALUES (:uuid, :user, :company, :status, 100, '2024-01-01', 'CONSULTANT',
                                FALSE, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("user", userUuid)
                .setParameter("company", UUID.randomUUID().toString())
                .setParameter("status", status)
                .executeUpdate();
    }

    private long eventCount() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM recruitment_events")
                .getSingleResult()).longValue();
    }

    private long claimCount(String payloadId) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery(
                                "SELECT COUNT(*) FROM recruitment_slack_inbound_dedupe WHERE payload_key = :key")
                        .setParameter("key", "interactions:" + payloadId)
                        .getSingleResult()).longValue());
    }

    private void assertNoClaim(String payloadId) {
        assertEquals(0, claimCount(payloadId), "fail-closed paths must leave no claim row");
    }

    private void assertNoNewEvents() {
        long after = QuarkusTransaction.requiringNew().call(this::eventCount);
        assertEquals(eventCountBefore, after,
                "P13 dispatch must append zero recruitment events (allowlist is empty)");
    }
}
