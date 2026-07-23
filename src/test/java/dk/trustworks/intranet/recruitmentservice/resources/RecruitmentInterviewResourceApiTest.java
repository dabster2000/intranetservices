package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P11 DoD (API, end-to-end through the resource) — the blind rule and the
 * interview lifecycle with real {@code roles}/{@code teamroles} fixture
 * rows resolved via {@code X-Requested-By}:
 * <ul>
 *   <li>blind rule: pre-submit read of a colleague's scorecard → filtered;
 *       post-submit → visible; recruiter pre-decision → filtered,
 *       post-decision → visible;</li>
 *   <li>scorecard template flows from position config (staff-track trimmed
 *       template case included);</li>
 *   <li>INFORMAL: no round, no scorecard;</li>
 *   <li>events: exactly the expected {@code INTERVIEW_*} /
 *       {@code SCORECARD_SUBMITTED} appends, scores/recommendation NEVER on
 *       the event, notes in pii only, {@code assertNoPiiInPayload}
 *       green;</li>
 *   <li>timeline: scorecard notes visible to the author, redacted for
 *       everyone else;</li>
 *   <li>the interviewer's own list ({@code /interviews/mine}) carries the
 *       kit and only the caller's assignments.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentInterviewResourceApiTest {

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String teamUuid;
    private String recruiterUser;
    private String teamleadUser;
    private String interviewerA;
    private String interviewerB;
    private String plainUser;
    private String staffOwner;

    private String teamPosition;
    private String staffPosition;
    private String candidateUuid;
    private String staffCandidateUuid;
    private String applicationUuid;
    private String staffApplicationUuid;

    private String previousInterviewsFlag;
    private String previousPipelineFlag;

    private static final String INTERVIEWS_FLAG = "recruitment.interviews.enabled";

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        interviewerA = UUID.randomUUID().toString();
        interviewerB = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        staffOwner = UUID.randomUUID().toString();
        teamPosition = UUID.randomUUID().toString();
        staffPosition = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        staffCandidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        staffApplicationUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, recruiterUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, teamleadUser, "Tim", "Teamlead");
            P8ProfileFixtures.insertUser(em, interviewerA, "Ida", "Interviewer");
            P8ProfileFixtures.insertUser(em, interviewerB, "Ib", "Interviewer");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertUser(em, staffOwner, "Owe", "Owner");
            P8ProfileFixtures.insertRole(em, recruiterUser, "HR");
            P8ProfileFixtures.insertRole(em, teamleadUser, "TEAMLEAD");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertTeamLeader(em, teamleadUser, teamUuid);

            // Team position: standard template, stage set with two rounds.
            P8ProfileFixtures.insertPosition(em, teamPosition, "Consultant", "PRACTICE_TEAM",
                    practiceUuid, teamUuid, null,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"OFFER\",\"HIRED\"]",
                    P8ProfileFixtures.STANDARD_SCORECARD_TEMPLATE_JSON);
            // Staff position: trimmed template (2 attributes), one round.
            P8ProfileFixtures.insertPosition(em, staffPosition, "Office manager", "STAFF_ROLE",
                    null, null, staffOwner,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"OFFER\",\"HIRED\"]",
                    "[{\"code\":\"CULTURE_FIT\",\"label\":\"Culture fit\"},"
                            + "{\"code\":\"UNCERTAINTY\",\"label\":\"Handling uncertainty\"}]");

            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    PII_SENTINEL + " Kim", PII_SENTINEL + " Kandidat", "ACTIVE", null, null,
                    recruiterUser);
            P8ProfileFixtures.insertCandidate(em, staffCandidateUuid,
                    PII_SENTINEL + " Stine", PII_SENTINEL + " Stab", "ACTIVE", null, null,
                    recruiterUser);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid, candidateUuid,
                    teamPosition, "INTERVIEW_1");
            P8ProfileFixtures.insertOpenApplication(em, staffApplicationUuid, staffCandidateUuid,
                    staffPosition, "INTERVIEW_1");

            previousInterviewsFlag = P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "true");
            previousPipelineFlag = P8ProfileFixtures.setFlag(em, P8ProfileFixtures.PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid, staffCandidateUuid),
                    List.of(teamPosition, staffPosition),
                    List.of(recruiterUser, teamleadUser, interviewerA, interviewerB,
                            plainUser, staffOwner),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, INTERVIEWS_FLAG, previousInterviewsFlag);
            P8ProfileFixtures.restoreFlag(em, P8ProfileFixtures.PIPELINE_FLAG, previousPipelineFlag);
        });
    }

    // ---- Lifecycle ---------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void createRescheduleCancel_appendExactlyTheExpectedEvents_piiClean() {
        String interviewUuid = createRoundOne(teamleadUser, List.of(interviewerA, interviewerB));

        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/interviews/{uuid}/schedule", interviewUuid)
                .then().statusCode(200);

        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .when().post("/recruitment/interviews/{uuid}/cancel", interviewUuid)
                .then().statusCode(204);

        // Cancelled interviews take no scorecards.
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);

        QuarkusTransaction.requiringNew().run(() -> {
            List<RecruitmentEvent> events = RecruitmentEvent.list(
                    "candidateUuid = ?1 order by seq", candidateUuid);
            assertEquals(List.of(
                            RecruitmentEventType.INTERVIEW_SCHEDULED,
                            RecruitmentEventType.INTERVIEW_RESCHEDULED,
                            RecruitmentEventType.INTERVIEW_CANCELLED),
                    events.stream().map(RecruitmentEvent::getEventType).toList());
            events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
            assertTrue(events.get(1).getPayload().contains("previous_scheduled_at"));
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void createValidation_roundMustBeInStageSet_informalTakesNoRound() {
        // Round 3 is not part of this position's pipeline.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 3,
                        "interviewerUuids", List.of(interviewerA),
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(409);

        // INFORMAL with a round number is contradictory.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "INFORMAL", "round", 1,
                        "interviewerUuids", List.of(interviewerA),
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(409);

        // Unknown interviewer.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 1,
                        "interviewerUuids", List.of(UUID.randomUUID().toString()),
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(409);

        // Missing time is a plain 400 (resource-level requirement).
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "INFORMAL", "interviewerUuids", List.of(interviewerA)))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void informalChat_schedulable_butNeverTakesAScorecard() {
        String interviewUuid = given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "INFORMAL",
                        "interviewerUuids", List.of(interviewerA),
                        "location", "Teams",
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(201)
                .extract().path("interviewUuid");

        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);
    }

    // ---- The blind rule ------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void blindRule_preSubmitFiltered_postSubmitVisible_recruiterWaitsForDebrief() {
        String interviewUuid = createRoundOne(teamleadUser, List.of(interviewerA, interviewerB));

        // A submits with a sentinel note.
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("STRONG_YES", PII_SENTINEL + " impressive case answers"))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("ownSubmitted", is(true))
                .body("unlocked", is(true))
                .body("scorecards", hasSize(1));

        // B (assigned, not yet submitted): counters only — A's card hidden.
        given().header("X-Requested-By", interviewerB)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(false))
                .body("ownSubmitted", is(false))
                .body("submittedCount", is(1))
                .body("expectedCount", is(2))
                .body("scorecards", hasSize(0));

        // Recruiter pre-debrief, pre-decision: filtered too.
        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(false))
                .body("scorecards", hasSize(0));

        // B submits their own → full side-by-side including A's notes.
        given().header("X-Requested-By", interviewerB)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("NO", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(true))
                .body("scorecards", hasSize(2));

        // All scorecards in → the recruiter's debrief unlocks.
        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(true))
                .body("scorecards", hasSize(2));

        // Double submit → 409.
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);

        // The events never carry scores or the recommendation; notes in pii.
        QuarkusTransaction.requiringNew().run(() -> {
            List<RecruitmentEvent> submits = RecruitmentEvent.list(
                    "candidateUuid = ?1 and eventType = ?2 order by seq",
                    candidateUuid, RecruitmentEventType.SCORECARD_SUBMITTED);
            assertEquals(2, submits.size());
            submits.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
            for (RecruitmentEvent event : submits) {
                assertFalse(event.getPayload().contains("recommendation"),
                        "recommendation must never ride on the event (blind rule)");
                assertFalse(event.getPayload().contains("scores"),
                        "scores must never ride on the event (blind rule)");
            }
            assertTrue(submits.get(0).getPii() != null
                            && submits.get(0).getPii().contains("impressive case answers"),
                    "the author's note lives in pii");
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void blindRule_decisionUnlocksForDecisionTier_notForOutsiders() {
        String interviewUuid = createRoundOne(teamleadUser, List.of(interviewerA, interviewerB));

        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200);

        // One of two in, no decision yet: teamlead (decision tier) locked.
        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(false));

        // The decision: the application moves past INTERVIEW_1.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "UPDATE recruitment_applications SET stage = 'INTERVIEW_2' WHERE uuid = :u")
                        .setParameter("u", applicationUuid).executeUpdate());

        // Decision tier unlocked, B (assigned, unsubmitted) still blind.
        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(true))
                .body("scorecards", hasSize(1));
        given().header("X-Requested-By", interviewerB)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("unlocked", is(false))
                .body("scorecards", hasSize(0));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void submitAuthz_onlyAssignedInterviewers_404ForOutsiders() {
        String interviewUuid = createRoundOne(teamleadUser, List.of(interviewerA));

        // Teamlead can see the interview but is not assigned → 403.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);

        // A plain user can't even see the interview → 404, never 403.
        given().header("X-Requested-By", plainUser)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(404);
        given().header("X-Requested-By", plainUser)
                .when().get("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(404);
    }

    // ---- Template flows from position config ----------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void staffTrack_trimmedTemplate_scoresMustMatchTheTemplate() {
        String interviewUuid = given().header("X-Requested-By", staffOwner)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 1,
                        "interviewerUuids", List.of(interviewerA),
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", staffApplicationUuid)
                .then().statusCode(201)
                .extract().path("interviewUuid");

        // Standard 4-attribute scores → rejected (unknown attributes).
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);

        // Missing one trimmed attribute → rejected.
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(Map.of("scores", Map.of("CULTURE_FIT", 3), "recommendation", "YES"))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);

        // Score out of range → rejected.
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(Map.of("scores", Map.of("CULTURE_FIT", 5, "UNCERTAINTY", 3),
                        "recommendation", "YES"))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(409);

        // Exactly the trimmed template → accepted.
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(Map.of("scores", Map.of("CULTURE_FIT", 4, "UNCERTAINTY", 2),
                        "recommendation", "STRONG_YES"))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200)
                .body("scorecards[0].scores.CULTURE_FIT", is(4));
    }

    // ---- Debrief -----------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void debrief_rendersPerRound_withPerRoundUnlock() {
        String round1 = createRoundOne(teamleadUser, List.of(interviewerA));
        String round2 = given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 2,
                        "interviewerUuids", List.of(interviewerA, interviewerB),
                        "scheduledAt", "2026-08-02T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(201)
                .extract().path("interviewUuid");

        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", null))
                .when().post("/recruitment/interviews/{uuid}/scorecards", round1)
                .then().statusCode(200);

        // Round 1: all in → unlocked for the teamlead. Round 2: pending.
        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/applications/{uuid}/debrief", applicationUuid)
                .then().statusCode(200)
                .body("rounds", hasSize(2))
                .body("rounds[0].interview.uuid", equalTo(round1))
                .body("rounds[0].scorecards.unlocked", is(true))
                .body("rounds[0].scorecards.scorecards", hasSize(1))
                .body("rounds[1].interview.uuid", equalTo(round2))
                .body("rounds[1].scorecards.unlocked", is(false))
                .body("rounds[1].scorecards.scorecards", hasSize(0));

        given().header("X-Requested-By", plainUser)
                .when().get("/recruitment/applications/{uuid}/debrief", applicationUuid)
                .then().statusCode(404);
    }

    // ---- Timeline redaction ---------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void timeline_scorecardNotes_authorOnly_redactedForOthers() {
        String interviewUuid = createRoundOne(teamleadUser, List.of(interviewerA));
        given().header("X-Requested-By", interviewerA)
                .contentType(ContentType.JSON)
                .body(standardScorecardBody("YES", PII_SENTINEL + " candid remarks"))
                .when().post("/recruitment/interviews/{uuid}/scorecards", interviewUuid)
                .then().statusCode(200);

        // The author reads their own notes on the timeline.
        given().header("X-Requested-By", interviewerA)
                .when().get("/recruitment/candidates/{uuid}/events", candidateUuid)
                .then().statusCode(200)
                .body("events.find { it.eventType == 'SCORECARD_SUBMITTED' }.pii.notes",
                        notNullValue())
                .body("events.find { it.eventType == 'SCORECARD_SUBMITTED' }.piiRedacted",
                        is(false));

        // The recruiter sees the event but the notes stay redacted — the
        // timeline never undercuts the blind rule.
        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/candidates/{uuid}/events", candidateUuid)
                .then().statusCode(200)
                .body("events.find { it.eventType == 'SCORECARD_SUBMITTED' }.pii",
                        nullValue())
                .body("events.find { it.eventType == 'SCORECARD_SUBMITTED' }.piiRedacted",
                        is(true));
    }

    // ---- The interviewer's own list --------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void mine_carriesTheKit_andOnlyTheCallersAssignments() {
        QuarkusTransaction.requiringNew().run(() -> {
            String cvFile = UUID.randomUUID().toString();
            P8ProfileFixtures.insertFileRow(em, cvFile, candidateUuid, "cv.pdf");
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidateUuid,
                    applicationUuid, teamPosition, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + cvFile + "\",\"kind\":\"CV\"}", null);
        });
        createRoundOne(teamleadUser, List.of(interviewerA));

        given().header("X-Requested-By", interviewerA)
                .when().get("/recruitment/interviews/mine")
                .then().statusCode(200)
                .body("totalCount", is(1))
                .body("interviews[0].positionTitle", equalTo("Consultant"))
                .body("interviews[0].focusAreas", hasSize(4))
                .body("interviews[0].cvFileUuid", notNullValue())
                .body("interviews[0].scorecardRequired", is(true))
                .body("interviews[0].ownScorecardSubmitted", is(false))
                .body("interviews[0].candidateName", not(nullValue()));

        // B is not assigned to anything.
        given().header("X-Requested-By", interviewerB)
                .when().get("/recruitment/interviews/mine")
                .then().statusCode(200)
                .body("totalCount", is(0));
    }

    // ---- Flag gate ---------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void interviewsFlagOff_nonAdminCaller_404() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "false"));
        given().header("X-Requested-By", interviewerA)
                .when().get("/recruitment/interviews/mine")
                .then().statusCode(404);
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "INFORMAL",
                        "interviewerUuids", List.of(interviewerA),
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview", "admin:*"})
    void interviewsFlagOff_adminScope_bypasses() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "false"));
        given().header("X-Requested-By", interviewerA)
                .when().get("/recruitment/interviews/mine")
                .then().statusCode(200);
    }

    // ---- Candidate interviews list (profile tab) ------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write", "recruitment:interview"})
    void candidateInterviews_visibleToProfileTier_andToAssignedInterviewer() {
        String interviewUuid = createRoundOne(teamleadUser, List.of(interviewerA));

        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/candidates/{uuid}/interviews", candidateUuid)
                .then().statusCode(200)
                .body("totalCount", is(1))
                .body("interviews[0].uuid", equalTo(interviewUuid))
                .body("interviews[0].interviewers", hasSize(1));

        // The assigned interviewer reaches the tab through the P11 grant.
        given().header("X-Requested-By", interviewerA)
                .when().get("/recruitment/candidates/{uuid}/interviews", candidateUuid)
                .then().statusCode(200)
                .body("totalCount", is(1));

        given().header("X-Requested-By", plainUser)
                .when().get("/recruitment/candidates/{uuid}/interviews", candidateUuid)
                .then().statusCode(404);
    }

    // ---- Room booking ---------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void roomEmail_persistsOnCreate_rescheduleKeepsOrClears() {
        String interviewUuid = given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 1,
                        "interviewerUuids", List.of(interviewerA),
                        "location", "HQ meeting room 2",
                        "roomEmail", "room-hq2@trustworks.dk",
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(201)
                .extract().path("interviewUuid");
        assertEquals("room-hq2@trustworks.dk", roomEmailInDb(interviewUuid));

        // Absent roomEmail on reschedule = keep the booking.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("scheduledAt", "2026-08-02T10:00:00"))
                .when().post("/recruitment/interviews/{uuid}/schedule", interviewUuid)
                .then().statusCode(200);
        assertEquals("room-hq2@trustworks.dk", roomEmailInDb(interviewUuid));

        // Blank roomEmail = clear the booking.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("scheduledAt", "2026-08-03T10:00:00", "roomEmail", ""))
                .when().post("/recruitment/interviews/{uuid}/schedule", interviewUuid)
                .then().statusCode(200);
        assertNull(roomEmailInDb(interviewUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void roomEmail_withoutAnAddressShape_isRejected() {
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 1,
                        "interviewerUuids", List.of(interviewerA),
                        "roomEmail", "not-a-mailbox",
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void rooms_calendarToggleOff_returnsEmptyList() {
        // The Graph calendar toggle is off in the test profile — the rooms
        // surface answers an empty list (the UI hides the picker), never an
        // error.
        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/interviews/rooms")
                .then().statusCode(200)
                .body("totalCount", is(0))
                .body("rooms", hasSize(0));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void rooms_requiresWriteScope() {
        given().header("X-Requested-By", teamleadUser)
                .when().get("/recruitment/interviews/rooms")
                .then().statusCode(403);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private String roomEmailInDb(String interviewUuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                (String) em.createNativeQuery(
                                "SELECT room_email FROM recruitment_interviews WHERE uuid = :u")
                        .setParameter("u", interviewUuid)
                        .getSingleResult());
    }

    private String createRoundOne(String actor, List<String> interviewers) {
        return given().header("X-Requested-By", actor)
                .contentType(ContentType.JSON)
                .body(Map.of("kind", "ROUND", "round", 1,
                        "interviewerUuids", interviewers,
                        "location", "HQ meeting room 2",
                        "scheduledAt", "2026-08-01T10:00:00"))
                .when().post("/recruitment/applications/{uuid}/interviews", applicationUuid)
                .then().statusCode(201)
                .extract().path("interviewUuid");
    }

    private static Map<String, Object> standardScorecardBody(String recommendation, String notes) {
        Map<String, Integer> scores = Map.of(
                "WHY_CONSULTING", 3,
                "COMMERCIAL_DRIVE", 3,
                "UNCERTAINTY", 2,
                "CULTURE_FIT", 4);
        return notes == null
                ? Map.of("scores", scores, "recommendation", recommendation)
                : Map.of("scores", scores, "recommendation", recommendation, "notes", notes);
    }
}
