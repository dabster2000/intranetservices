package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.slack.SlackAssistantService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P25 §DoD — the {@code app_mention} key through the real P13 dispatch
 * pipeline: master gate off → inert (DISABLED before any handler),
 * assistant toggle off / pipeline flag off → mention silently ignored
 * with 200 (HANDLED, no ephemeral — events carry no user-visible
 * response), all gates on → the assistant service receives the
 * envelope's channel/thread/text. The service itself is mocked here —
 * its behavior is {@code SlackAssistantServiceTest}'s job; this test
 * owns the wiring and the gate semantics.
 */
@QuarkusTest
class SlackInboundP25ApiTest {

    private static final String INBOUND_PATH = "/recruitment/slack/inbound";
    private static final String SOURCE_HEADER = "X-Slack-Inbound-Source";
    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";
    private static final String ASSISTANT_FLAG = "recruitment.slack.assistant.enabled";
    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String DISABLED_TEXT = "This feature is currently disabled.";

    private static final String CHANNEL = "C0P25CHAN01";
    private static final String THREAD_TS = "1721733600.000300";

    @Inject
    EntityManager em;

    @InjectMock
    SlackAssistantService assistantService;

    private String actorUuid;
    private String actorSlackId;
    private final Map<String, String> previousFlags = new HashMap<>();

    @BeforeEach
    void seed() {
        actorUuid = UUID.randomUUID().toString();
        actorSlackId = "U" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        QuarkusTransaction.requiringNew().run(() -> {
            for (String flag : new String[]{MASTER_FLAG, ASSISTANT_FLAG, PIPELINE_FLAG}) {
                previousFlags.put(flag, P8ProfileFixtures.setFlag(em, flag, "true"));
            }
            P8ProfileFixtures.insertUser(em, actorUuid, "Mona", "Mentioner");
            em.createNativeQuery("UPDATE user SET slackusername = :s WHERE uuid = :u")
                    .setParameter("s", actorSlackId).setParameter("u", actorUuid)
                    .executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO userstatus (uuid, useruuid, companyuuid, status, allocation,
                                                    statusdate, type, is_tw_bonus_eligible,
                                                    created_at, updated_at, created_by)
                            VALUES (:uuid, :user, :company, 'ACTIVE', 100, '2024-01-01', 'CONSULTANT',
                                    FALSE, NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("user", actorUuid)
                    .setParameter("company", UUID.randomUUID().toString())
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            previousFlags.forEach((flag, previous) ->
                    P8ProfileFixtures.restoreFlag(em, flag, previous));
            previousFlags.clear();
            em.createNativeQuery("DELETE FROM recruitment_slack_inbound_dedupe WHERE slack_team_id = 'T-p25test'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM userstatus WHERE useruuid = :u")
                    .setParameter("u", actorUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid = :u")
                    .setParameter("u", actorUuid).executeUpdate();
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void allGatesOn_mentionReachesTheAssistantWithTheEnvelopeDetail() {
        postMention("where are we with Jens Hansen?").then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());
        // The handler hands off to a ManagedExecutor — verify with a timeout.
        verify(assistantService, timeout(5000)).answerMention(
                eq(actorUuid), eq(CHANNEL), eq(THREAD_TS),
                eq("where are we with Jens Hansen?"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void assistantToggleOff_mentionSilentlyIgnoredWith200() {
        setFlag(ASSISTANT_FLAG, "false");
        postMention("status?").then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());
        verifyNoInteractions(assistantService);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void pipelineFlagOff_mentionSilentlyIgnoredWith200() {
        setFlag(PIPELINE_FLAG, "false");
        postMention("status?").then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());
        verifyNoInteractions(assistantService);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void masterGateOff_dispatchAnswersDisabledBeforeAnyHandler() {
        setFlag(MASTER_FLAG, "false");
        postMention("status?").then().statusCode(200)
                .body("disposition", equalTo("DISABLED"))
                .body("ephemeralText", equalTo(DISABLED_TEXT));
        verifyNoInteractions(assistantService);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void unlinkedSlackUser_failsClosedBeforeTheAssistant() {
        given().contentType(ContentType.JSON)
                .header(SOURCE_HEADER, "bff")
                .body(envelope("U0UNLINKED99", "status?"))
                .post(INBOUND_PATH).then().statusCode(200)
                .body("disposition", equalTo("NOT_LINKED"));
        verifyNoInteractions(assistantService);
    }

    // ---- Helpers ---------------------------------------------------------

    private io.restassured.response.Response postMention(String text) {
        return given().contentType(ContentType.JSON)
                .header(SOURCE_HEADER, "bff")
                .body(envelope(actorSlackId, text))
                .post(INBOUND_PATH);
    }

    private Map<String, Object> envelope(String slackUserId, String text) {
        Map<String, Object> map = new HashMap<>();
        map.put("surface", "events");
        map.put("payloadId", "Ev" + UUID.randomUUID());
        map.put("slackUserId", slackUserId);
        map.put("slackTeamId", "T-p25test");
        map.put("kind", "event_callback");
        map.put("handlerKey", "app_mention");
        map.put("text", text);
        map.put("channelId", CHANNEL);
        map.put("messageTs", THREAD_TS);
        return map;
    }

    private void setFlag(String flag, String value) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, flag, value));
    }
}
