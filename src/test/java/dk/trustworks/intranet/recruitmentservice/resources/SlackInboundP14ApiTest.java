package dk.trustworks.intranet.recruitmentservice.resources;

import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.element.PlainTextInputElement;
import com.slack.api.model.view.View;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P14 §DoD — the four Slack intake features end-to-end through the real
 * dispatch pipeline against the local DB, with only the Slack transport
 * mocked: {@code /refer} → {@code REFERRAL_SUBMITTED(origin=slack)},
 * triage buttons → the P6 one-shot triage command (+ ping rewrite +
 * idempotency), capture → {@code NOTE_ADDED} with permalink + privacy,
 * {@code /candidates} → the P8 read-matrix authz matrix, per-feature
 * toggles and the master gate. DB-state assertions run in fresh
 * transactions (findings §P11 flush lesson).
 */
@QuarkusTest
class SlackInboundP14ApiTest {

    private static final String INBOUND_PATH = "/recruitment/slack/inbound";
    private static final String SOURCE_HEADER = "X-Slack-Inbound-Source";
    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";
    private static final String REFER_FLAG = "recruitment.slack.refer.enabled";
    private static final String TRIAGE_FLAG = "recruitment.slack.triage-actions.enabled";
    private static final String CAPTURE_FLAG = "recruitment.slack.capture.enabled";
    private static final String LOOKUP_FLAG = "recruitment.slack.lookup.enabled";
    private static final String DISABLED_TEXT = "This feature is currently disabled.";

    private static final String PING_CHANNEL = "C0AAAAAAAA";
    private static final String PING_TS = "1721733600.000100";

    @Inject
    EntityManager em;

    @InjectMock
    SlackService slackService;

    // Distinct users per tier; fresh per test (parallel-safe names).
    private String employeeUuid;
    private String recruiterUuid;
    private String uninvolvedUuid;
    private String interviewerUuid;
    private String employeeSlackId;
    private String recruiterSlackId;
    private String uninvolvedSlackId;
    private String interviewerSlackId;

    private String practiceUuid;
    private String normalPositionUuid;
    private String partnerPositionUuid;
    private String normalCandidateUuid;
    private String partnerCandidateUuid;
    private String referralUuid;
    private String marker;

    private final Map<String, String> previousFlags = new HashMap<>();

    @BeforeEach
    void seed() {
        marker = UUID.randomUUID().toString().substring(0, 8);
        employeeUuid = UUID.randomUUID().toString();
        recruiterUuid = UUID.randomUUID().toString();
        uninvolvedUuid = UUID.randomUUID().toString();
        interviewerUuid = UUID.randomUUID().toString();
        employeeSlackId = slackId();
        recruiterSlackId = slackId();
        uninvolvedSlackId = slackId();
        interviewerSlackId = slackId();
        practiceUuid = UUID.randomUUID().toString();
        normalPositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        normalCandidateUuid = UUID.randomUUID().toString();
        partnerCandidateUuid = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            for (String flag : List.of(MASTER_FLAG, REFER_FLAG, TRIAGE_FLAG, CAPTURE_FLAG, LOOKUP_FLAG)) {
                previousFlags.put(flag, P8ProfileFixtures.setFlag(em, flag, "true"));
            }
            seedUser(employeeUuid, "Emma", "Employee", employeeSlackId, null);
            seedUser(recruiterUuid, "Rita", "Recruiter", recruiterSlackId, "HR");
            seedUser(uninvolvedUuid, "Uno", "Uninvolved", uninvolvedSlackId, null);
            seedUser(interviewerUuid, "Ivan", "Interviewer", interviewerSlackId, null);

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, normalPositionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertPosition(em, partnerPositionUuid, "Equity Partner",
                    "PARTNER", null, null, null);

            P8ProfileFixtures.insertCandidate(em, normalCandidateUuid,
                    "LookupJane" + marker, "Jensen", "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertCandidate(em, partnerCandidateUuid,
                    "LookupPartner" + marker, "Poulsen", "ACTIVE", null, null, "test");
            String normalApplication = UUID.randomUUID().toString();
            P8ProfileFixtures.insertOpenApplication(em, normalApplication,
                    normalCandidateUuid, normalPositionUuid, "INTERVIEW_1");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    partnerCandidateUuid, partnerPositionUuid, "SCREENING");
            P8ProfileFixtures.insertInterview(em, UUID.randomUUID().toString(),
                    normalApplication, "ROUND", 1,
                    "[\"" + interviewerUuid + "\"]", "SCHEDULED");

            insertReferral(referralUuid, "Refferee " + marker, employeeUuid);
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            previousFlags.forEach((flag, previous) ->
                    P8ProfileFixtures.restoreFlag(em, flag, previous));
            previousFlags.clear();
            List<String> users = List.of(employeeUuid, recruiterUuid, uninvolvedUuid, interviewerUuid);
            em.createNativeQuery("DELETE FROM recruitment_slack_inbound_dedupe WHERE slack_team_id = 'T-p14test'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_events WHERE actor_uuid IN (:u)")
                    .setParameter("u", users).executeUpdate();
            // Triage-created candidates (created inside the tests).
            List<?> created = em.createNativeQuery(
                            "SELECT uuid FROM recruitment_candidates WHERE created_by_useruuid IN (:u)")
                    .setParameter("u", users).getResultList();
            @SuppressWarnings("unchecked")
            List<String> createdUuids = (List<String>) created;
            // FK order: the triaged referral references its created candidate.
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid = :r")
                    .setParameter("r", referralUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    concat(createdUuids, List.of(normalCandidateUuid, partnerCandidateUuid)),
                    List.of(normalPositionUuid, partnerPositionUuid), List.of(), null);
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", practiceUuid).executeUpdate();
            for (String user : users) {
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
    // Master gate + per-feature toggles (DoD: each toggle off → ephemeral;
    // master off → all four inert)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void masterGateOff_allFourFeaturesInert() throws Exception {
        setFlag(MASTER_FLAG, "false");
        for (Map<String, Object> envelope : List.of(
                referCommand(employeeSlackId),
                lookupCommand(recruiterSlackId, "jane"),
                captureShortcut(employeeSlackId, "text"),
                triageButton(recruiterSlackId, "recruitment_triage_create"))) {
            postInbound(envelope).then().statusCode(200)
                    .body("disposition", equalTo("DISABLED"))
                    .body("ephemeralText", equalTo(DISABLED_TEXT));
        }
        verify(slackService, never()).openView(anyString(), any());
        assertEquals(0, eventsByActors());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void featureTogglesOff_eachAnswersDisabled_masterOn() throws Exception {
        for (String flag : List.of(REFER_FLAG, TRIAGE_FLAG, CAPTURE_FLAG, LOOKUP_FLAG)) {
            setFlag(flag, "false");
        }
        for (Map<String, Object> envelope : List.of(
                referCommand(employeeSlackId),
                lookupCommand(recruiterSlackId, "jane"),
                captureShortcut(employeeSlackId, "text"),
                triageButton(recruiterSlackId, "recruitment_triage_dismiss"))) {
            postInbound(envelope).then().statusCode(200)
                    .body("disposition", equalTo("DISABLED"))
                    .body("ephemeralText", equalTo(DISABLED_TEXT));
        }
        verify(slackService, never()).openView(anyString(), any());
        assertEquals(0, eventsByActors());
    }

    // =========================================================================
    // /refer (DoD: E2E → REFERRAL_SUBMITTED(origin=slack); inline errors)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void referCommand_opensTheModal() throws Exception {
        postInbound(referCommand(employeeSlackId)).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", nullValue());
        ArgumentCaptor<View> view = ArgumentCaptor.forClass(View.class);
        verify(slackService).openView(eq("trg-" + marker), view.capture());
        assertEquals("recruitment_refer_submit", view.getValue().getCallbackId());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void referSubmit_createsReferral_originSlack_confirmationView() {
        Map<String, Object> envelope = viewSubmission(employeeSlackId, "recruitment_refer_submit",
                referStateValues("Nadia Newhire " + marker,
                        "https://www.linkedin.com/in/nadia", "COLLEAGUE",
                        "Brilliant platform engineer, great with clients."),
                null);
        postInbound(envelope).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("responseAction", containsString("\"response_action\":\"update\""))
                .body("responseAction", containsString("/recruitment/refer"));

        QuarkusTransaction.requiringNew().run(() -> {
            Map<String, Object> referral = singleRow("""
                    SELECT status, candidate_name, linkedin_url FROM recruitment_referrals
                    WHERE referrer_uuid = :u AND candidate_name LIKE :name
                    """, Map.of("u", employeeUuid, "name", "Nadia Newhire%"));
            assertEquals("SUBMITTED", referral.get("status"));
            assertEquals("https://www.linkedin.com/in/nadia", referral.get("linkedin_url"));

            Map<String, Object> event = singleRow("""
                    SELECT payload, pii FROM recruitment_events
                    WHERE event_type = 'REFERRAL_SUBMITTED' AND actor_uuid = :u
                    """, Map.of("u", employeeUuid));
            String payload = (String) event.get("payload");
            String pii = (String) event.get("pii");
            assertTrue(payload.contains("\"origin\":\"slack\""), "provenance stamped");
            assertFalse(payload.contains("Nadia"), "names never in payload");
            assertFalse(payload.contains("Brilliant"), "why-text never in payload");
            assertTrue(pii.contains("Nadia Newhire"), "personal fields live in pii");
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void referSubmit_invalidLinkedin_inlineErrorOnTheRightBlock_noRow() {
        Map<String, Object> envelope = viewSubmission(employeeSlackId, "recruitment_refer_submit",
                referStateValues("Bad Link " + marker,
                        "https://evil.example/in/jane", "COLLEAGUE", "why"),
                null);
        postInbound(envelope).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("responseAction", containsString("\"response_action\":\"errors\""))
                .body("responseAction", containsString("linkedin_url"));
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(0L, count("""
                        SELECT COUNT(*) FROM recruitment_referrals
                        WHERE referrer_uuid = :u AND candidate_name LIKE 'Bad Link%'
                        """, Map.of("u", employeeUuid))));
    }

    // =========================================================================
    // Triage buttons (DoD: create incl. AI-prefill path, dismiss w/ reason,
    // chat.update rewrite, stale click, one-command-one-event idempotency)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void triageCreateButton_nonRecruiter_friendlyDenial_noModal() throws Exception {
        postInbound(triageButton(employeeSlackId, "recruitment_triage_create"))
                .then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", containsString("reserved for the recruitment team"));
        verify(slackService, never()).openView(anyString(), any());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void triageCreateButton_opensPrefilledModal() throws Exception {
        postInbound(triageButton(recruiterSlackId, "recruitment_triage_create"))
                .then().statusCode(200)
                .body("disposition", equalTo("HANDLED"));
        ArgumentCaptor<View> view = ArgumentCaptor.forClass(View.class);
        verify(slackService).openView(anyString(), view.capture());
        assertEquals("recruitment_triage_create_submit", view.getValue().getCallbackId());
        assertTrue(view.getValue().getPrivateMetadata().contains(referralUuid),
                "the modal round-trips the referral id");
        PlainTextInputElement firstName = view.getValue().getBlocks().stream()
                .filter(InputBlock.class::isInstance).map(InputBlock.class::cast)
                .filter(b -> "first_name".equals(b.getBlockId()))
                .map(b -> (PlainTextInputElement) b.getElement())
                .findFirst().orElseThrow();
        assertEquals("Refferee", firstName.getInitialValue(), "name prefilled from the referral");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void triageCreateSubmit_createsCandidate_rewritesPing_thenConflictsOnRepeat() {
        Map<String, Object> envelope = viewSubmission(recruiterSlackId,
                "recruitment_triage_create_submit",
                triageCreateStateValues("Nadia" + marker, "Newhire"),
                triageMetadata());
        postInbound(envelope).then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("responseAction", containsString("\"response_action\":\"update\""))
                .body("responseAction", containsString("/recruitment/candidates/"));

        // The ping rewrite removed the buttons (chat.update to plain outcome).
        verify(slackService).updateMessage(eq(PING_CHANNEL), eq(PING_TS),
                contains("Triaged"), eq(null));

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("TRIAGED", singleRow(
                    "SELECT status FROM recruitment_referrals WHERE uuid = :r",
                    Map.of("r", referralUuid)).get("status"));
            assertEquals(1L, count("""
                    SELECT COUNT(*) FROM recruitment_candidates
                    WHERE first_name = :f AND created_by_useruuid = :u
                    """, Map.of("f", "Nadia" + marker, "u", recruiterUuid)));
            String payload = (String) singleRow("""
                    SELECT payload FROM recruitment_events
                    WHERE event_type = 'REFERRAL_TRIAGED' AND actor_uuid = :u
                    """, Map.of("u", recruiterUuid)).get("payload");
            assertTrue(payload.contains("\"origin\":\"slack\""));
            assertTrue(payload.contains("\"outcome\":\"CANDIDATE_CREATED\""));
        });

        // A genuinely repeated submission (NEW payload id — the dedupe can't
        // save us) must conflict on the one-shot rule: one candidate, one event.
        postInbound(viewSubmission(recruiterSlackId, "recruitment_triage_create_submit",
                triageCreateStateValues("Nadia" + marker, "Again"), triageMetadata()))
                .then().statusCode(200)
                .body("responseAction", containsString("Already handled"));
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1L, count("""
                    SELECT COUNT(*) FROM recruitment_candidates WHERE created_by_useruuid = :u
                    """, Map.of("u", recruiterUuid)), "one command execution — one candidate");
            assertEquals(1L, count("""
                    SELECT COUNT(*) FROM recruitment_events
                    WHERE event_type = 'REFERRAL_TRIAGED' AND actor_uuid = :u
                    """, Map.of("u", recruiterUuid)), "one event");
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void duplicatePayloadId_secondDeliveryNeverReachesTheHandler() {
        Map<String, Object> envelope = viewSubmission(employeeSlackId, "recruitment_refer_submit",
                referStateValues("Dedupe Dana " + marker, null, "OTHER", "solid"), null);
        postInbound(envelope).then().statusCode(200).body("disposition", equalTo("HANDLED"));
        postInbound(envelope).then().statusCode(200).body("disposition", equalTo("DUPLICATE"));
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(1L, count("""
                        SELECT COUNT(*) FROM recruitment_referrals
                        WHERE referrer_uuid = :u AND candidate_name LIKE 'Dedupe Dana%'
                        """, Map.of("u", employeeUuid)), "a Slack retry executes exactly once"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void triageDismissSubmit_closesReferralWithReason_rewritesPing() {
        postInbound(viewSubmission(recruiterSlackId, "recruitment_triage_dismiss_submit",
                dismissStateValues("NOT_RELEVANT"), triageMetadata()))
                .then().statusCode(200)
                .body("responseAction", containsString("\"response_action\":\"update\""))
                .body("responseAction", containsString("Not relevant"));
        verify(slackService).updateMessage(eq(PING_CHANNEL), eq(PING_TS),
                contains("Dismissed"), eq(null));
        QuarkusTransaction.requiringNew().run(() -> {
            Map<String, Object> referral = singleRow(
                    "SELECT status, closed_reason FROM recruitment_referrals WHERE uuid = :r",
                    Map.of("r", referralUuid));
            assertEquals("CLOSED", referral.get("status"));
            assertEquals("NOT_RELEVANT", referral.get("closed_reason"));
            String payload = (String) singleRow("""
                    SELECT payload FROM recruitment_events
                    WHERE event_type = 'REFERRAL_TRIAGED' AND actor_uuid = :u
                    """, Map.of("u", recruiterUuid)).get("payload");
            assertTrue(payload.contains("\"outcome\":\"DISMISSED\""));
            assertTrue(payload.contains("\"origin\":\"slack\""));
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void staleButtonClick_afterTriage_rewritesPing_opensNoModal() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_referrals SET status = 'CLOSED',
                                    closed_reason = 'OTHER' WHERE uuid = :r
                                """)
                        .setParameter("r", referralUuid).executeUpdate());
        postInbound(triageButton(recruiterSlackId, "recruitment_triage_create"))
                .then().statusCode(200)
                .body("ephemeralText", containsString("already handled"));
        verify(slackService).updateMessage(eq(PING_CHANNEL), eq(PING_TS),
                contains("Dismissed"), eq(null));
        verify(slackService, never()).openView(anyString(), any());
    }

    // =========================================================================
    // Capture (DoD: NOTE_ADDED w/ permalink + editable text + private flag;
    // scope-filtered candidate select; uniform no-access)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void captureShortcut_opensModal_withEditablePrefill() throws Exception {
        postInbound(captureShortcut(employeeSlackId, "met her at the conference — strong profile"))
                .then().statusCode(200).body("disposition", equalTo("HANDLED"));
        ArgumentCaptor<View> view = ArgumentCaptor.forClass(View.class);
        verify(slackService).openView(anyString(), view.capture());
        assertEquals("recruitment_capture_submit", view.getValue().getCallbackId());
        assertTrue(view.getValue().getBlocks().stream()
                        .filter(InputBlock.class::isInstance).map(InputBlock.class::cast)
                        .anyMatch(b -> "note_text".equals(b.getBlockId())
                                && "met her at the conference — strong profile".equals(
                                ((PlainTextInputElement) b.getElement()).getInitialValue())),
                "the message text prefills the editable note field");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void captureSubmit_appendsNote_withPermalinkOriginAndPrivacy() {
        when(slackService.getPermalink(PING_CHANNEL, PING_TS))
                .thenReturn("https://trustworks.slack.com/archives/C0AAAAAAAA/p1721733600000100");
        postInbound(viewSubmission(recruiterSlackId, "recruitment_capture_submit",
                captureStateValues(normalCandidateUuid, "Edited note text", true),
                captureMetadata()))
                .then().statusCode(200)
                .body("responseAction", containsString("\"response_action\":\"update\""))
                .body("responseAction", containsString("(private)"));

        QuarkusTransaction.requiringNew().run(() -> {
            Map<String, Object> event = singleRow("""
                    SELECT payload, pii FROM recruitment_events
                    WHERE event_type = 'NOTE_ADDED' AND candidate_uuid = :c
                    """, Map.of("c", normalCandidateUuid));
            String payload = (String) event.get("payload");
            assertTrue(payload.contains("\"origin\":\"slack\""));
            assertTrue(payload.contains("\"slack_permalink\":\"https://trustworks.slack.com/archives/"));
            assertTrue(payload.contains("\"private\":true"));
            assertFalse(payload.contains("Edited note text"), "note text never in payload");
            assertTrue(((String) event.get("pii")).contains("Edited note text"));
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void captureSubmit_unauthorizedOrUnknownCandidate_uniformInlineError_zeroEvents() {
        // Uninvolved employee on a real candidate…
        postInbound(viewSubmission(uninvolvedSlackId, "recruitment_capture_submit",
                captureStateValues(normalCandidateUuid, "note", false), captureMetadata()))
                .then().statusCode(200)
                .body("responseAction", containsString("\"response_action\":\"errors\""))
                .body("responseAction", containsString("you have access to"));
        // …and a recruiter on a nonexistent uuid answer the SAME error.
        postInbound(viewSubmission(recruiterSlackId, "recruitment_capture_submit",
                captureStateValues(UUID.randomUUID().toString(), "note", false), captureMetadata()))
                .then().statusCode(200)
                .body("responseAction", containsString("you have access to"));
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(0L, count("""
                        SELECT COUNT(*) FROM recruitment_events
                        WHERE event_type = 'NOTE_ADDED' AND candidate_uuid = :c
                        """, Map.of("c", normalCandidateUuid))));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void candidateSearch_authzFiltered_andNeverClaimsDedupe() {
        // The recruiter finds the candidate…
        postInbound(blockSuggestion(recruiterSlackId, "LookupJane" + marker))
                .then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("responseAction", containsString(normalCandidateUuid));
        // …the uninvolved employee gets an empty list for the same query.
        postInbound(blockSuggestion(uninvolvedSlackId, "LookupJane" + marker))
                .then().statusCode(200)
                .body("responseAction", containsString("\"options\":[]"));
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(0L, count("""
                        SELECT COUNT(*) FROM recruitment_slack_inbound_dedupe
                        WHERE payload_key LIKE 'interactions:Vsuggest%'
                        """, Map.of()), "block_suggestion is query-only — never claimed"));
    }

    // =========================================================================
    // /candidates lookup (DoD: authz matrix = the P8 read matrix; uniform
    // no-access; no referrer identity)
    // =========================================================================

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void lookup_recruiterGetsCard_uninvolvedGetsUniformNoAccess() {
        String query = "LookupJane" + marker;
        // Recruiter (HR): full card with stage, days-in-stage and deep link.
        String card = postInbound(lookupCommand(recruiterSlackId, query))
                .then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .extract().path("ephemeralText");
        assertNotNull(card);
        assertTrue(card.contains("LookupJane" + marker), "name on the card");
        assertTrue(card.contains("Senior Consultant"), "position on the card");
        assertTrue(card.contains("Interview 1"), "stage on the card");
        assertTrue(card.contains("in stage"), "days-in-stage on the card");
        assertTrue(card.contains("/recruitment/candidates/" + normalCandidateUuid), "deep link");
        assertFalse(card.toLowerCase().contains("refer"), "no referrer identity, ever");

        // Uninvolved employee: the uniform sentence — no existence signal.
        postInbound(lookupCommand(uninvolvedSlackId, query))
                .then().statusCode(200)
                .body("ephemeralText", equalTo(
                        "No candidates matching \"" + query + "\" that you have access to."));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void lookup_interviewerSeesExactlyTheirAssignedCandidate() {
        postInbound(lookupCommand(interviewerSlackId, "LookupJane" + marker))
                .then().statusCode(200)
                .body("ephemeralText", containsString("LookupJane" + marker));
        postInbound(lookupCommand(interviewerSlackId, "LookupPartner" + marker))
                .then().statusCode(200)
                .body("ephemeralText", equalTo(
                        "No candidates matching \"LookupPartner" + marker
                                + "\" that you have access to."));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void lookup_partnerTrackCandidate_hiddenFromNonCircleRecruiter() {
        // HR outside the circle: the hard circle filter wins over the
        // recruiter tier (spec §7.2) — uniform no-access.
        postInbound(lookupCommand(recruiterSlackId, "LookupPartner" + marker))
                .then().statusCode(200)
                .body("ephemeralText", equalTo(
                        "No candidates matching \"LookupPartner" + marker
                                + "\" that you have access to."));
        // A circle member reads it (query-level proof of the circle tier).
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertCircleMember(em, partnerPositionUuid, recruiterUuid));
        postInbound(lookupCommand(recruiterSlackId, "LookupPartner" + marker))
                .then().statusCode(200)
                .body("ephemeralText", containsString("LookupPartner" + marker));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:admin"})
    void lookup_emptyQuery_usageHelp() {
        postInbound(lookupCommand(recruiterSlackId, ""))
                .then().statusCode(200)
                .body("disposition", equalTo("HANDLED"))
                .body("ephemeralText", containsString("/candidates jane"));
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

    private void insertReferral(String uuid, String candidateName, String referrerUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_referrals
                            (uuid, referrer_uuid, referrer_relation, candidate_name, email,
                             linkedin_url, why_text, status, submitted_at, version,
                             created_at, updated_at, created_by)
                        VALUES (:uuid, :referrer, 'COLLEAGUE', :name, 'ref@example.com',
                                'https://linkedin.com/in/ref', 'why text', 'SUBMITTED',
                                UTC_TIMESTAMP(3), 0, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("referrer", referrerUuid)
                .setParameter("name", candidateName)
                .executeUpdate();
    }

    private void setFlag(String flag, String value) {
        QuarkusTransaction.requiringNew().run(() -> P8ProfileFixtures.setFlag(em, flag, value));
    }

    private static String slackId() {
        return "U" + UUID.randomUUID().toString().substring(0, 10).replace("-", "").toUpperCase();
    }

    private Map<String, Object> baseEnvelope(String slackUserId, String kind, String handlerKey) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("surface", kind.equals("command") ? "commands" : "interactions");
        envelope.put("payloadId", "trg-" + UUID.randomUUID());
        envelope.put("slackUserId", slackUserId);
        envelope.put("slackTeamId", "T-p14test");
        envelope.put("kind", kind);
        envelope.put("handlerKey", handlerKey);
        return envelope;
    }

    private Map<String, Object> referCommand(String slackUserId) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "command", "/refer");
        envelope.put("triggerId", "trg-" + marker);
        return envelope;
    }

    private Map<String, Object> lookupCommand(String slackUserId, String text) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "command", "/candidates");
        envelope.put("text", text);
        envelope.put("triggerId", "trg-" + UUID.randomUUID());
        return envelope;
    }

    private Map<String, Object> captureShortcut(String slackUserId, String messageText) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "message_action", "recruitment_capture");
        envelope.put("triggerId", "trg-" + UUID.randomUUID());
        envelope.put("channelId", PING_CHANNEL);
        envelope.put("messageTs", PING_TS);
        envelope.put("messageText", messageText);
        return envelope;
    }

    private Map<String, Object> triageButton(String slackUserId, String actionId) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "block_actions", actionId);
        envelope.put("triggerId", "trg-" + UUID.randomUUID());
        envelope.put("actionValue", referralUuid);
        envelope.put("channelId", PING_CHANNEL);
        envelope.put("messageTs", PING_TS);
        return envelope;
    }

    private Map<String, Object> viewSubmission(String slackUserId, String callbackId,
                                               String stateValuesJson, String privateMetadata) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "view_submission", callbackId);
        envelope.put("stateValues", stateValuesJson);
        if (privateMetadata != null) {
            envelope.put("privateMetadata", privateMetadata);
        }
        return envelope;
    }

    private Map<String, Object> blockSuggestion(String slackUserId, String query) {
        Map<String, Object> envelope = baseEnvelope(slackUserId, "block_suggestion",
                "recruitment_capture_candidate_select");
        envelope.put("payloadId", "Vsuggest" + UUID.randomUUID());
        envelope.put("text", query);
        return envelope;
    }

    private String triageMetadata() {
        return "{\"referralUuid\":\"" + referralUuid + "\",\"channelId\":\"" + PING_CHANNEL
                + "\",\"messageTs\":\"" + PING_TS + "\"}";
    }

    private String captureMetadata() {
        return "{\"channelId\":\"" + PING_CHANNEL + "\",\"messageTs\":\"" + PING_TS + "\"}";
    }

    private static String referStateValues(String name, String linkedin, String relation, String why) {
        return """
                {
                  "candidate_name": {"a": {"type": "plain_text_input", "value": "%s"}},
                  %s
                  "relation": {"a": {"type": "static_select", "selected_option": {"value": "%s"}}},
                  "why_text": {"a": {"type": "plain_text_input", "value": "%s"}}
                }
                """.formatted(name,
                linkedin == null ? ""
                        : "\"linkedin_url\": {\"a\": {\"type\": \"plain_text_input\", \"value\": \""
                        + linkedin + "\"}},",
                relation, why);
    }

    private static String triageCreateStateValues(String firstName, String lastName) {
        return """
                {
                  "first_name": {"a": {"type": "plain_text_input", "value": "%s"}},
                  "last_name": {"a": {"type": "plain_text_input", "value": "%s"}},
                  "experience_level": {"a": {"type": "static_select", "selected_option": {"value": "SENIOR"}}}
                }
                """.formatted(firstName, lastName);
    }

    private static String dismissStateValues(String reason) {
        return """
                {"dismiss_reason": {"a": {"type": "static_select", "selected_option": {"value": "%s"}}}}
                """.formatted(reason);
    }

    private static String captureStateValues(String candidateUuid, String text, boolean isPrivate) {
        return """
                {
                  "capture_candidate": {"a": {"type": "external_select", "selected_option": {"value": "%s"}}},
                  "note_text": {"a": {"type": "plain_text_input", "value": "%s"}},
                  "note_private": {"a": {"type": "checkboxes", "selected_options": [%s]}}
                }
                """.formatted(candidateUuid, text, isPrivate ? "{\"value\": \"private\"}" : "");
    }

    private static io.restassured.response.Response postInbound(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .header(SOURCE_HEADER, "bff")
                .body(body)
                .post(INBOUND_PATH);
    }

    private long eventsByActors() {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("""
                                SELECT COUNT(*) FROM recruitment_events WHERE actor_uuid IN (:u)
                                """)
                        .setParameter("u", List.of(employeeUuid, recruiterUuid,
                                uninvolvedUuid, interviewerUuid))
                        .getSingleResult()).longValue());
    }

    private long count(String sql, Map<String, Object> params) {
        var query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
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

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
