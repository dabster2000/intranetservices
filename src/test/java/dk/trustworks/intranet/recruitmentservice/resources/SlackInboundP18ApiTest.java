package dk.trustworks.intranet.recruitmentservice.resources;

import com.slack.api.model.view.View;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P18 §DoD — the Slack scorecard modal end-to-end through the real dispatch
 * pipeline against the local DB, with only the Slack transport mocked:
 * nudge-DM button → modal (template-driven, {@code private_metadata} =
 * interview uuid) → submit → {@code SCORECARD_SUBMITTED(origin=slack)} via
 * the SAME command as the web form. Re-authorization is fail-closed on
 * every round-tripped id (forged/stale value or metadata, non-assigned
 * actor, already-submitted ⇒ zero events); duplicates die in the P13
 * dedupe or on the one-per-interviewer rule; the per-feature toggle and
 * the master gate render everything inert. DB-state assertions run in
 * fresh transactions (findings §P11 flush lesson).
 */
@QuarkusTest
class SlackInboundP18ApiTest {

    private static final String INBOUND_PATH = "/recruitment/slack/inbound";
    private static final String SOURCE_HEADER = "X-Slack-Inbound-Source";
    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";
    private static final String SCORECARD_FLAG = "recruitment.slack.scorecard.enabled";
    private static final String DISABLED_TEXT = "This feature is currently disabled.";

    @Inject
    EntityManager em;

    @InjectMock
    SlackService slackService;

    private String interviewerUuid;
    private String coInterviewerUuid;
    private String recruiterUuid;
    private String interviewerSlackId;
    private String coInterviewerSlackId;
    private String recruiterSlackId;

    private String practiceUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String interviewUuid;
    private String soloInterviewUuid;
    private String informalInterviewUuid;
    private String marker;

    private final Map<String, String> previousFlags = new HashMap<>();

    @BeforeEach
    void seed() {
        marker = UUID.randomUUID().toString().substring(0, 8);
        interviewerUuid = UUID.randomUUID().toString();
        coInterviewerUuid = UUID.randomUUID().toString();
        recruiterUuid = UUID.randomUUID().toString();
        interviewerSlackId = slackId();
        coInterviewerSlackId = slackId();
        recruiterSlackId = slackId();
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        interviewUuid = UUID.randomUUID().toString();
        soloInterviewUuid = UUID.randomUUID().toString();
        informalInterviewUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            for (String flag : List.of(MASTER_FLAG, SCORECARD_FLAG)) {
                previousFlags.put(flag, P8ProfileFixtures.setFlag(em, flag, "true"));
            }
            seedUser(interviewerUuid, "Ivan", "Interviewer", interviewerSlackId, null);
            seedUser(coInterviewerUuid, "Carla", "CoInterviewer", coInterviewerSlackId, null);
            seedUser(recruiterUuid, "Rita", "Recruiter", recruiterSlackId, "HR");

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"OFFER\",\"HIRED\"]",
                    P8ProfileFixtures.STANDARD_SCORECARD_TEMPLATE_JSON);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Scoree" + marker, "Sørensen", "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");
            // Two-interviewer round (progress copy), a solo round (last-card
            // copy) and an informal chat (never scoreable).
            P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 1,
                    "[\"" + interviewerUuid + "\",\"" + coInterviewerUuid + "\"]", "SCHEDULED");
            P8ProfileFixtures.insertInterview(em, soloInterviewUuid, applicationUuid, "ROUND", 2,
                    "[\"" + interviewerUuid + "\"]", "SCHEDULED");
            P8ProfileFixtures.insertInterview(em, informalInterviewUuid, applicationUuid,
                    "INFORMAL", null, "[\"" + interviewerUuid + "\"]", "SCHEDULED");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            previousFlags.forEach((flag, previous) ->
                    P8ProfileFixtures.restoreFlag(em, flag, previous));
            previousFlags.clear();
            em.createNativeQuery("DELETE FROM recruitment_slack_inbound_dedupe "
                            + "WHERE slack_team_id = 'T-p18test'")
                    .executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(), null);
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", practiceUuid).executeUpdate();
            for (String user : List.of(interviewerUuid, coInterviewerUuid, recruiterUuid)) {
                em.createNativeQuery("DELETE FROM userstatus WHERE useruuid = :u")
                        .setParameter("u", user).executeUpdate();
                em.createNativeQuery("DELETE FROM roles WHERE useruuid = :u")
                        .setParameter("u", user).executeUpdate();
                em.createNativeQuery("DELETE FROM user WHERE uuid = :u")
                        .setParameter("u", user).executeUpdate();
            }
        });
    }

    // =========================================================================
    // Gates (DoD: toggle off → DMs fall back / handlers inert; master off → inert)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void masterGateOff_bothHandlersInert() throws Exception {
        setFlag(MASTER_FLAG, "false");
        postInbound(openButton(interviewerSlackId, interviewUuid)).then().statusCode(200)
                .body("disposition", equalTo("DISABLED"))
                .body("ephemeralText", equalTo(DISABLED_TEXT));
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                fullStateValues("YES", null))).then().statusCode(200)
                .body("disposition", equalTo("DISABLED"));
        verify(slackService, never()).openView(anyString(), any());
        assertEquals(0, scorecardCount());
        assertEquals(0, submittedEventCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void scorecardToggleOff_buttonEphemeral_submitOutcomeView() throws Exception {
        setFlag(SCORECARD_FLAG, "false");
        postInbound(openButton(interviewerSlackId, interviewUuid)).then().statusCode(200)
                .body("disposition", equalTo("DISABLED"))
                .body("ephemeralText", equalTo(DISABLED_TEXT));
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                fullStateValues("YES", null))).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("responseAction", containsString("Feature disabled"));
        verify(slackService, never()).openView(anyString(), any());
        assertEquals(0, scorecardCount());
        assertEquals(0, submittedEventCount());
    }

    // =========================================================================
    // The open button — modal from the position's template, fail-closed re-auth
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void openButton_assignedInterviewer_opensTemplateDrivenModal() throws Exception {
        postInbound(openButton(interviewerSlackId, interviewUuid)).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());

        ArgumentCaptor<View> view = ArgumentCaptor.forClass(View.class);
        verify(slackService).openView(anyString(), view.capture());
        View modal = view.getValue();
        assertEquals("recruitment_scorecard_submit", modal.getCallbackId());
        assertEquals(interviewUuid, modal.getPrivateMetadata(),
                "private_metadata carries the interview uuid (plan §P18)");
        String blocks = modal.getBlocks().toString();
        for (String code : List.of("WHY_CONSULTING", "COMMERCIAL_DRIVE", "UNCERTAINTY",
                "CULTURE_FIT")) {
            assertTrue(blocks.contains("score_" + code),
                    "one select per template attribute: " + code);
        }
        assertTrue(blocks.contains("recommendation"), "recommendation radios present");
        assertTrue(blocks.contains("scorecard_notes"), "notes input present");
        assertTrue(blocks.contains("Scoree" + marker), "intro names the candidate");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void openButton_failClosed_uniformDeny_zeroSideEffects() throws Exception {
        // Non-assigned actor (a recruiter, even) — assignment is the rule.
        postInbound(openButton(recruiterSlackId, interviewUuid)).then().statusCode(200)
                .body("ephemeralText", containsString("isn't available to you"));
        // Unknown uuid, garbage value, cancelled round, informal chat.
        postInbound(openButton(interviewerSlackId, UUID.randomUUID().toString()))
                .then().statusCode(200)
                .body("ephemeralText", containsString("isn't available to you"));
        postInbound(openButton(interviewerSlackId, "not-a-uuid")).then().statusCode(200)
                .body("ephemeralText", containsString("isn't available to you"));
        postInbound(openButton(interviewerSlackId, informalInterviewUuid)).then().statusCode(200)
                .body("ephemeralText", containsString("isn't available to you"));
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_interviews SET status = 'CANCELLED' "
                                + "WHERE uuid = :u")
                        .setParameter("u", soloInterviewUuid).executeUpdate());
        postInbound(openButton(interviewerSlackId, soloInterviewUuid)).then().statusCode(200)
                .body("ephemeralText", containsString("isn't available to you"));

        verify(slackService, never()).openView(anyString(), any());
        assertEquals(0, scorecardCount());
        assertEquals(0, submittedEventCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void openButton_alreadySubmitted_politeNotice_noModal() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertScorecard(em, UUID.randomUUID().toString(),
                        interviewUuid, interviewerUuid, "YES"));
        postInbound(openButton(interviewerSlackId, interviewUuid)).then().statusCode(200)
                .body("ephemeralText", containsString("already submitted"));
        verify(slackService, never()).openView(anyString(), any());
    }

    // =========================================================================
    // The submission — same command as the web form, origin=slack
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_happyPath_sameCommandAsWeb_originSlack_notesInPiiOnly() {
        String notes = "Structured thinker, hesitated on pricing " + marker;
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                fullStateValues("YES", notes))).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("responseAction", containsString("\"response_action\":\"update\""))
                .body("responseAction", containsString("Scorecard submitted"))
                .body("responseAction", containsString("Waiting for 1 more scorecard"));

        QuarkusTransaction.requiringNew().run(() -> {
            Map<String, Object> card = singleRow("""
                    SELECT scores, recommendation FROM recruitment_scorecards
                    WHERE interview_uuid = :i AND interviewer_uuid = :u
                    """, Map.of("i", interviewUuid, "u", interviewerUuid));
            assertEquals("YES", card.get("recommendation"));
            assertTrue(String.valueOf(card.get("scores")).contains("\"WHY_CONSULTING\": 4")
                            || String.valueOf(card.get("scores")).contains("\"WHY_CONSULTING\":4"),
                    "scores persisted from the selects: " + card.get("scores"));
            // First submission marks the interview HELD — the web rule.
            assertEquals("HELD", singleRow(
                    "SELECT status FROM recruitment_interviews WHERE uuid = :i",
                    Map.of("i", interviewUuid)).get("status"));

            List<RecruitmentEvent> events = RecruitmentEvent.list(
                    "candidateUuid = ?1 and eventType = ?2",
                    candidateUuid, RecruitmentEventType.SCORECARD_SUBMITTED);
            assertEquals(1, events.size());
            RecruitmentEvent event = events.getFirst();
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
            assertTrue(event.getPayload().contains("\"origin\":\"slack\""),
                    "provenance for adoption reporting: " + event.getPayload());
            assertFalse(event.getPayload().contains("recommendation"),
                    "blind rule: recommendation never rides on the event");
            assertFalse(event.getPayload().contains(notes), "notes never in payload");
            assertNotNull(event.getPii());
            assertTrue(event.getPii().contains(notes), "notes live in pii");
            assertEquals(interviewerUuid, event.getActorUuid(),
                    "the resolved Slack actor is the event actor");
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_lastScorecard_confirmationAnnouncesDebriefReady() {
        postInbound(scorecardSubmission(interviewerSlackId, soloInterviewUuid,
                fullStateValues("STRONG_YES", null))).then().statusCode(200)
                .body("responseAction", containsString("the debrief is ready"))
                .body("responseAction", containsString("decision owner"));
        // The debrief-ready notification itself is the P12 reactor's job,
        // driven by this SCORECARD_SUBMITTED event — asserted in
        // RecruitmentSlackReactorTest (same rule, shared code).
        assertEquals(1, submittedEventCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_duplicateSlackRetry_diesInDedupe_oneEventOneRow() {
        Map<String, Object> envelope = scorecardSubmission(interviewerSlackId, interviewUuid,
                fullStateValues("YES", null));
        postInbound(envelope).then().statusCode(200).body("disposition", equalTo("HANDLED"));
        postInbound(envelope).then().statusCode(200).body("disposition", equalTo("DUPLICATE"));
        assertEquals(1, scorecardCount());
        assertEquals(1, submittedEventCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_repeatedAttempt_conflictsOnOneShotRule_oneEventOneRow() {
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                fullStateValues("YES", null))).then().statusCode(200);
        // A NEW payload id (not a Slack retry): the command's
        // one-per-interviewer rule answers, and the P14 rollback semantics
        // leave no second row and no second event.
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                fullStateValues("NO", null))).then().statusCode(200)
                .body("responseAction", containsString("Already submitted"));
        assertEquals(1, scorecardCount());
        assertEquals(1, submittedEventCount());
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals("YES", singleRow(
                                "SELECT recommendation FROM recruitment_scorecards "
                                        + "WHERE interview_uuid = :i",
                                Map.of("i", interviewUuid)).get("recommendation"),
                        "the first submission stands untouched"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_forgedOrStaleMetadata_failsClosed_zeroEvents() {
        // Unknown interview uuid.
        postInbound(scorecardSubmission(interviewerSlackId, UUID.randomUUID().toString(),
                fullStateValues("YES", null))).then().statusCode(200)
                .body("responseAction", containsString("lost track of its interview"));
        // Garbage metadata.
        postInbound(scorecardSubmission(interviewerSlackId, "forged-metadata",
                fullStateValues("YES", null))).then().statusCode(200)
                .body("responseAction", containsString("lost track of its interview"));
        // Valid interview, non-assigned actor — the command refuses.
        postInbound(scorecardSubmission(recruiterSlackId, interviewUuid,
                fullStateValues("YES", null))).then().statusCode(200)
                .body("responseAction", containsString("Not available"));
        assertEquals(0, scorecardCount());
        assertEquals(0, submittedEventCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_forgedState_inlineErrorsOnTheOffendingBlock_zeroRows() {
        // Score 9 can't come from the 1–4 select — a forged payload.
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                stateValues(Map.of("WHY_CONSULTING", "9", "COMMERCIAL_DRIVE", "3",
                        "UNCERTAINTY", "3", "CULTURE_FIT", "3"), "YES", null)))
                .then().statusCode(200)
                .body("responseAction", containsString("\"response_action\":\"errors\""))
                .body("responseAction", containsString("score_WHY_CONSULTING"));
        // Missing recommendation (forged radio value degrades to missing).
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                stateValues(Map.of("WHY_CONSULTING", "3", "COMMERCIAL_DRIVE", "3",
                        "UNCERTAINTY", "3", "CULTURE_FIT", "3"), "MAYBE_SO", null)))
                .then().statusCode(200)
                .body("responseAction", containsString("\"response_action\":\"errors\""))
                .body("responseAction", containsString("recommendation"));
        // Missing score for one attribute.
        postInbound(scorecardSubmission(interviewerSlackId, interviewUuid,
                stateValues(Map.of("WHY_CONSULTING", "3", "COMMERCIAL_DRIVE", "3",
                        "UNCERTAINTY", "3"), "YES", null)))
                .then().statusCode(200)
                .body("responseAction", containsString("score_CULTURE_FIT"));
        assertEquals(0, scorecardCount());
        assertEquals(0, submittedEventCount());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void submit_informalInterview_terminalOutcome_zeroEvents() {
        postInbound(scorecardSubmission(interviewerSlackId, informalInterviewUuid,
                fullStateValues("YES", null))).then().statusCode(200)
                .body("responseAction", containsString("Not available"));
        assertEquals(0, scorecardCount());
        assertEquals(0, submittedEventCount());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void seedUser(String uuid, String first, String last, String slackId, String role) {
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
        if (role != null) {
            P8ProfileFixtures.insertRole(em, uuid, role);
        }
    }

    private void setFlag(String flag, String value) {
        QuarkusTransaction.requiringNew().run(() -> P8ProfileFixtures.setFlag(em, flag, value));
    }

    private static String slackId() {
        return "U" + UUID.randomUUID().toString().substring(0, 10).replace("-", "").toUpperCase();
    }

    private Map<String, Object> baseEnvelope(String slackUserId, String kind, String handlerKey) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("surface", "interactions");
        envelope.put("payloadId", "trg-" + UUID.randomUUID());
        envelope.put("slackUserId", slackUserId);
        envelope.put("slackTeamId", "T-p18test");
        envelope.put("kind", kind);
        envelope.put("handlerKey", handlerKey);
        return envelope;
    }

    private Map<String, Object> openButton(String slackUserId, String interviewUuid) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "block_actions",
                "recruitment_scorecard_open");
        envelope.put("triggerId", "trg-" + UUID.randomUUID());
        envelope.put("actionValue", interviewUuid);
        return envelope;
    }

    private Map<String, Object> scorecardSubmission(String slackUserId, String privateMetadata,
                                                    String stateValuesJson) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "view_submission",
                "recruitment_scorecard_submit");
        envelope.put("privateMetadata", privateMetadata);
        envelope.put("stateValues", stateValuesJson);
        return envelope;
    }

    /** All four standard-template scores (4/3/3/3) + recommendation + notes. */
    private static String fullStateValues(String recommendation, String notes) {
        return stateValues(Map.of("WHY_CONSULTING", "4", "COMMERCIAL_DRIVE", "3",
                "UNCERTAINTY", "3", "CULTURE_FIT", "3"), recommendation, notes);
    }

    private static String stateValues(Map<String, String> scores, String recommendation,
                                      String notes) {
        StringBuilder sb = new StringBuilder("{");
        scores.forEach((code, value) -> sb.append("\"score_").append(code)
                .append("\": {\"a\": {\"type\": \"static_select\", "
                        + "\"selected_option\": {\"value\": \"").append(value).append("\"}}},"));
        if (recommendation != null) {
            sb.append("\"recommendation\": {\"a\": {\"type\": \"radio_buttons\", "
                            + "\"selected_option\": {\"value\": \"").append(recommendation)
                    .append("\"}}},");
        }
        if (notes != null) {
            sb.append("\"scorecard_notes\": {\"a\": {\"type\": \"plain_text_input\", "
                    + "\"value\": \"").append(notes).append("\"}},");
        }
        sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }

    private static io.restassured.response.Response postInbound(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .header(SOURCE_HEADER, "bff")
                .body(body)
                .post(INBOUND_PATH);
    }

    private long scorecardCount() {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("""
                                SELECT COUNT(*) FROM recruitment_scorecards s
                                JOIN recruitment_interviews i ON i.uuid = s.interview_uuid
                                WHERE i.application_uuid = :a
                                """)
                        .setParameter("a", applicationUuid)
                        .getSingleResult()).longValue());
    }

    private long submittedEventCount() {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("""
                                SELECT COUNT(*) FROM recruitment_events
                                WHERE candidate_uuid = :c AND event_type = 'SCORECARD_SUBMITTED'
                                """)
                        .setParameter("c", candidateUuid)
                        .getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> singleRow(String sql, Map<String, Object> params) {
        var query = em.createNativeQuery(sql, jakarta.persistence.Tuple.class);
        params.forEach(query::setParameter);
        List<jakarta.persistence.Tuple> rows = query.getResultList();
        assertEquals(1, rows.size(), "expected exactly one row for: " + sql);
        Map<String, Object> map = new HashMap<>();
        rows.getFirst().getElements().forEach(e ->
                map.put(e.getAlias().toLowerCase(), rows.getFirst().get(e)));
        return map;
    }
}
