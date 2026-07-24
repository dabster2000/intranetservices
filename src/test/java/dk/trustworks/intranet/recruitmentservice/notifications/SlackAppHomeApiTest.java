package dk.trustworks.intranet.recruitmentservice.notifications;

import com.slack.api.model.view.View;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
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
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P23 §DoD — App Home end-to-end through the real dispatch pipeline
 * against the local DB, with only the Slack transport mocked:
 * {@code app_home_opened} → role-aware {@code views.publish} mirroring the
 * P17 landing queue. Master gate off ⇒ the event is ignored; app-home
 * toggle or pipeline flag off ⇒ no publish at all (Slack keeps the default
 * empty Home); partner/blind filtering rides the landing read model. Also
 * covers the P23 outcome-modal path of the scorecard-open button (App Home
 * block actions carry no {@code response_url}).
 */
@QuarkusTest
class SlackAppHomeApiTest {

    private static final String INBOUND_PATH = "/recruitment/slack/inbound";
    private static final String SOURCE_HEADER = "X-Slack-Inbound-Source";
    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";
    private static final String APP_HOME_FLAG = "recruitment.slack.app-home.enabled";
    private static final String SCORECARD_FLAG = "recruitment.slack.scorecard.enabled";
    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String DISABLED_TEXT = "This feature is currently disabled.";

    @Inject
    EntityManager em;

    @InjectMock
    SlackService slackService;

    private String interviewerUuid;
    private String employeeUuid;
    private String interviewerSlackId;
    private String employeeSlackId;

    private String practiceUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String overdueInterviewUuid;
    private String upcomingInterviewUuid;
    private String marker;

    private final Map<String, String> previousFlags = new HashMap<>();

    @BeforeEach
    void seed() {
        marker = UUID.randomUUID().toString().substring(0, 8);
        interviewerUuid = UUID.randomUUID().toString();
        employeeUuid = UUID.randomUUID().toString();
        interviewerSlackId = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        employeeSlackId = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        overdueInterviewUuid = UUID.randomUUID().toString();
        upcomingInterviewUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            for (String flag : List.of(MASTER_FLAG, APP_HOME_FLAG, SCORECARD_FLAG, PIPELINE_FLAG)) {
                previousFlags.put(flag, P8ProfileFixtures.setFlag(em, flag, "true"));
            }
            seedUser(interviewerUuid, "Ivan", "Interviewer", interviewerSlackId);
            seedUser(employeeUuid, "Emma", "Employee", employeeSlackId);

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Homer" + marker, "Hansen", "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");
            // One round in the past (overdue-scorecard task) and one in the
            // future (the upcoming-interviews section).
            P8ProfileFixtures.insertInterviewHoursAgo(em, overdueInterviewUuid, applicationUuid,
                    "ROUND", 1, "[\"" + interviewerUuid + "\"]", "SCHEDULED", 30);
            P8ProfileFixtures.insertInterview(em, upcomingInterviewUuid, applicationUuid,
                    "ROUND", 2, "[\"" + interviewerUuid + "\"]", "SCHEDULED");
            // The employee's referral — free text carries the PII sentinel,
            // which must never surface on the Home tab.
            P12NotificationFixtures.insertReferral(em, employeeUuid, "Reffi " + marker,
                    PII_SENTINEL + " secret hallway context", "SUBMITTED", null);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            previousFlags.forEach((flag, previous) ->
                    P8ProfileFixtures.restoreFlag(em, flag, previous));
            previousFlags.clear();
            em.createNativeQuery("DELETE FROM recruitment_slack_inbound_dedupe "
                            + "WHERE slack_team_id = 'T-p23test'")
                    .executeUpdate();
            P12NotificationFixtures.deleteReferralsBy(em, employeeUuid);
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(), null);
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", practiceUuid).executeUpdate();
            for (String user : List.of(interviewerUuid, employeeUuid)) {
                em.createNativeQuery("DELETE FROM userstatus WHERE useruuid = :u")
                        .setParameter("u", user).executeUpdate();
                em.createNativeQuery("DELETE FROM user WHERE uuid = :u")
                        .setParameter("u", user).executeUpdate();
            }
        });
    }

    // =========================================================================
    // Gates (DoD: toggles off → no Home publish; master gate off → ignored)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void masterGateOff_appHomeOpenedIgnored() throws Exception {
        setFlag(MASTER_FLAG, "false");
        postInbound(homeOpened(interviewerSlackId)).then().statusCode(200)
                .body("disposition", equalTo("DISABLED"));
        verify(slackService, never()).publishView(anyString(), any());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void appHomeToggleOff_noPublish_slackKeepsDefaultHome() throws Exception {
        setFlag(APP_HOME_FLAG, "false");
        postInbound(homeOpened(interviewerSlackId)).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());
        verify(slackService, never()).publishView(anyString(), any());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void pipelineOff_noPublish_darkModuleLeaksNothing() throws Exception {
        setFlag(PIPELINE_FLAG, "false");
        postInbound(homeOpened(interviewerSlackId)).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"));
        verify(slackService, never()).publishView(anyString(), any());
    }

    // =========================================================================
    // Role-aware content (DoD: each role sees exactly their queue)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void interviewer_homeShowsScorecardTaskAndUpcoming_withScorecardButton() throws Exception {
        postInbound(homeOpened(interviewerSlackId)).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"));

        View home = capturePublishedView(interviewerSlackId);
        assertEquals("home", home.getType());
        String blocks = home.getBlocks().toString();
        assertTrue(blocks.contains("Scorecards waiting for you"),
                "the overdue scorecard renders as a task section");
        assertTrue(blocks.contains("Homer" + marker), "the candidate is named");
        assertTrue(blocks.contains("Senior Consultant"), "the position is named");
        assertTrue(blocks.contains("Your upcoming interviews"),
                "the future round renders in the upcoming section");
        assertTrue(blocks.contains("recruitment_scorecard_open"),
                "with the P18 toggle on, the task row carries the scorecard button");
        assertTrue(blocks.contains(overdueInterviewUuid),
                "the button value is the overdue interview uuid");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void scorecardToggleOff_taskRowDegradesToDeepLink() throws Exception {
        setFlag(SCORECARD_FLAG, "false");
        postInbound(homeOpened(interviewerSlackId)).then().statusCode(200);

        View home = capturePublishedView(interviewerSlackId);
        String blocks = home.getBlocks().toString();
        assertTrue(blocks.contains("Scorecards waiting for you"));
        assertFalse(blocks.contains("recruitment_scorecard_open"),
                "toggle off ⇒ no button — the explicit degradation chain");
        assertTrue(blocks.contains("/recruitment/interviews"),
                "the deep link remains the path to the scorecard");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void employee_homeShowsOwnReferrals_neverTheirFreeText() throws Exception {
        postInbound(homeOpened(employeeSlackId)).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"));

        View home = capturePublishedView(employeeSlackId);
        String blocks = home.getBlocks().toString();
        assertTrue(blocks.contains("Your referrals"), "the referral section renders");
        assertTrue(blocks.contains("Reffi " + marker), "the referred name (typed by the referrer)");
        assertTrue(blocks.contains("Waiting for triage"), "milestone status in plain language");
        assertFalse(blocks.contains("Scorecards waiting"),
                "an uninvolved employee has no interviewer tasks");
        assertFalse(blocks.contains(PII_SENTINEL),
                "referral why-text (free text) never reaches the Home tab");
    }

    // =========================================================================
    // Scorecard button from App Home (no response_url → outcome modal)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void homeScorecardButton_denyWithoutResponseUrl_opensOutcomeModal() throws Exception {
        // The employee is not an assigned interviewer — fail closed. From
        // App Home there is no response_url, so the deny must surface as a
        // small outcome modal instead of vanishing.
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("surface", "interactions");
        envelope.put("payloadId", "trg-" + UUID.randomUUID());
        envelope.put("slackUserId", employeeSlackId);
        envelope.put("slackTeamId", "T-p23test");
        envelope.put("kind", "block_actions");
        envelope.put("handlerKey", "recruitment_scorecard_open");
        envelope.put("triggerId", "trg-" + UUID.randomUUID());
        envelope.put("actionValue", overdueInterviewUuid);
        postInbound(envelope).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());

        ArgumentCaptor<View> view = ArgumentCaptor.forClass(View.class);
        verify(slackService).openView(anyString(), view.capture());
        assertTrue(view.getValue().getBlocks().toString().contains("isn't available to you"),
                "the uniform deny renders inside the outcome modal");
        verify(slackService, never()).publishView(anyString(), any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private View capturePublishedView(String slackUserId) throws Exception {
        ArgumentCaptor<View> view = ArgumentCaptor.forClass(View.class);
        verify(slackService).publishView(eq(slackUserId), view.capture());
        return view.getValue();
    }

    private Map<String, Object> homeOpened(String slackUserId) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("surface", "events");
        envelope.put("payloadId", "Ev" + UUID.randomUUID());
        envelope.put("slackUserId", slackUserId);
        envelope.put("slackTeamId", "T-p23test");
        envelope.put("kind", "event_callback");
        envelope.put("handlerKey", "app_home_opened");
        return envelope;
    }

    private static io.restassured.response.Response postInbound(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .header(SOURCE_HEADER, "bff")
                .body(body)
                .post(INBOUND_PATH);
    }

    private void seedUser(String uuid, String first, String last, String slackId) {
        P8ProfileFixtures.insertUser(em, uuid, first, last);
        em.createNativeQuery("UPDATE user SET slackusername = :s WHERE uuid = :u")
                .setParameter("s", slackId).setParameter("u", uuid).executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO userstatus (uuid, useruuid, companyuuid, status, allocation,
                                                statusdate, type, is_tw_bonus_eligible,
                                                created_at, updated_at, created_by)
                        VALUES (:uuid, :user, :company, 'ACTIVE', 100, '2024-01-01', 'CONSULTANT',
                                FALSE, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("user", uuid)
                .setParameter("company", UUID.randomUUID().toString())
                .executeUpdate();
    }

    private void setFlag(String flag, String value) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, flag, value));
    }
}
