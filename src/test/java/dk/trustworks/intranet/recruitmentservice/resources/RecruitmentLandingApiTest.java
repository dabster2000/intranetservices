package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P17 DoD — task-list and section correctness per role fixture (spec §7.2
 * matrix) against the real endpoint and local DB:
 * <ul>
 *   <li>recruiter (HR) — the world: aggregates, pipelines, feed;</li>
 *   <li>teamlead — own position's decision/idle tasks, own pipelines;</li>
 *   <li>practice lead — read access, no decision-owned tasks;</li>
 *   <li>interviewer (plain employee with an assignment) — scorecard tasks
 *       only, no pipelines, no feed;</li>
 *   <li>employee — the EMPLOYEE shape (client redirects to /refer);</li>
 *   <li>partner-track circle stays a hard filter in every section;</li>
 *   <li>flag gate: off + non-admin scope → 404; admin bypass works.</li>
 * </ul>
 * The shared local DB may carry rows from other suites, so assertions are
 * containment-scoped to this class's uuid-unique fixtures, never global
 * counts.
 */
@QuarkusTest
class RecruitmentLandingApiTest {

    private static final String PIPELINE_FLAG = "recruitment.pipeline.enabled";

    @Inject
    EntityManager em;

    private String marker;
    private String practiceUuid;
    private String teamUuid;
    private String recruiterUser;
    private String teamleadUser;
    private String practiceLeadUser;
    private String interviewerUser;
    private String employeeUser;
    private String circleOwnerUser;

    private String teamPositionUuid;
    private String partnerPositionUuid;
    private String idleCandidateUuid;
    private String idleApplicationUuid;
    private String partnerCandidateUuid;
    private String partnerApplicationUuid;
    private String hiredCandidateUuid;
    private String hiredApplicationUuid;
    private String interviewUuid;
    private String referralUuid;
    private String pendingEmailUuid;

    /** TEAMLEAD role, leads no team, owns nothing — reads widely, owns nothing. */
    private String wideTeamleadUser;

    private String previousFlag;

    @BeforeEach
    void seed() {
        marker = "LndT" + UUID.randomUUID().toString().substring(0, 8);
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        wideTeamleadUser = UUID.randomUUID().toString();
        practiceLeadUser = UUID.randomUUID().toString();
        interviewerUser = UUID.randomUUID().toString();
        employeeUser = UUID.randomUUID().toString();
        circleOwnerUser = UUID.randomUUID().toString();
        teamPositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        idleCandidateUuid = UUID.randomUUID().toString();
        idleApplicationUuid = UUID.randomUUID().toString();
        partnerCandidateUuid = UUID.randomUUID().toString();
        partnerApplicationUuid = UUID.randomUUID().toString();
        hiredCandidateUuid = UUID.randomUUID().toString();
        hiredApplicationUuid = UUID.randomUUID().toString();
        interviewUuid = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();
        pendingEmailUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, recruiterUser, marker, "Recruiter");
            P8ProfileFixtures.insertRole(em, recruiterUser, "HR");
            P8ProfileFixtures.insertUser(em, teamleadUser, marker, "Teamlead");
            P8ProfileFixtures.insertUser(em, practiceLeadUser, marker, "Practicelead");
            P8ProfileFixtures.insertUser(em, interviewerUser, marker, "Interviewer");
            P8ProfileFixtures.insertUser(em, employeeUser, marker, "Employee");
            P8ProfileFixtures.insertUser(em, circleOwnerUser, marker, "Circleowner");

            P8ProfileFixtures.insertUser(em, wideTeamleadUser, marker, "Wideteamlead");
            P8ProfileFixtures.insertRole(em, wideTeamleadUser, "TEAMLEAD");

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPracticeLead(em, practiceLeadUser, practiceUuid);
            // The team belongs to the practice — the hop "Your pipelines"
            // walks to decide that a team lead owns their practice's openings.
            P8ProfileFixtures.insertTeam(em, teamUuid, practiceUuid);
            P8ProfileFixtures.insertTeamLeader(em, teamleadUser, teamUuid);

            // A practice-team position led by the teamlead's team, with an
            // idle candidate (10 days in SCREENING) and an interviewer
            // whose round-1 scorecard is overdue (held 30 h ago).
            P8ProfileFixtures.insertPosition(em, teamPositionUuid, marker + " TeamPos",
                    "PRACTICE_TEAM", practiceUuid, teamUuid, null);
            P8ProfileFixtures.insertCandidate(em, idleCandidateUuid,
                    marker, "Idle", "ACTIVE", null, null, recruiterUser);
            P8ProfileFixtures.insertOpenApplication(em, idleApplicationUuid,
                    idleCandidateUuid, teamPositionUuid, "SCREENING");
            P8ProfileFixtures.backdateApplicationStageEntry(em, idleApplicationUuid, 10);
            P8ProfileFixtures.insertInterviewHoursAgo(em, interviewUuid, idleApplicationUuid,
                    "ROUND", 1, "[\"" + interviewerUser + "\"]", "HELD", 30);

            // Someone already hired onto the same position, backdated so it
            // would land in the idle bucket too if it were counted. markHired
            // leaves terminal NULL, so only an explicit stage exclusion keeps
            // this row out of the KPI row and the pipeline counts.
            P8ProfileFixtures.insertCandidate(em, hiredCandidateUuid,
                    marker, "Hired", "HIRED", null, null, recruiterUser);
            P8ProfileFixtures.insertOpenApplication(em, hiredApplicationUuid,
                    hiredCandidateUuid, teamPositionUuid, "HIRED");
            P8ProfileFixtures.backdateApplicationStageEntry(em, hiredApplicationUuid, 10);

            // A partner-track position with its own candidate — invisible
            // outside the circle in every section.
            P8ProfileFixtures.insertPosition(em, partnerPositionUuid, marker + " PartnerPos",
                    "PARTNER", null, null, null);
            em.createNativeQuery("""
                            INSERT INTO recruitment_circle_members
                                (position_uuid, user_uuid, role_in_circle, added_at, added_by_uuid)
                            VALUES (:p, :u, 'OWNER', NOW(3), :u)
                            """)
                    .setParameter("p", partnerPositionUuid)
                    .setParameter("u", circleOwnerUser).executeUpdate();
            P8ProfileFixtures.insertCandidate(em, partnerCandidateUuid,
                    marker, "Partner", "ACTIVE", null, null, circleOwnerUser);
            P8ProfileFixtures.insertOpenApplication(em, partnerApplicationUuid,
                    partnerCandidateUuid, partnerPositionUuid, "SCREENING");
            P8ProfileFixtures.backdateApplicationStageEntry(em, partnerApplicationUuid, 10);
            P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", partnerCandidateUuid,
                    partnerApplicationUuid, partnerPositionUuid, "USER", circleOwnerUser,
                    "CIRCLE", "{\"origin\":\"manual\"}", null);

            // Recruiter queue rows: one SUBMITTED referral + one PENDING email.
            em.createNativeQuery("""
                            INSERT INTO recruitment_referrals
                                (uuid, referrer_uuid, referrer_relation, candidate_name, why_text,
                                 status, submitted_at, created_at, updated_at, created_by)
                            VALUES (:uuid, :referrer, 'COLLEAGUE', :name, 'Great fit',
                                    'SUBMITTED', UTC_TIMESTAMP(3), NOW(), NOW(), :referrer)
                            """)
                    .setParameter("uuid", referralUuid)
                    .setParameter("referrer", employeeUser)
                    .setParameter("name", marker + " Referred").executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_pending_emails
                                (uuid, candidate_uuid, template_key, reason, to_email, subject,
                                 body, status, created_at, updated_at, created_by)
                            VALUES (:uuid, :candidate, 'REJECTION_POST_INTERVIEW', 'REVIEW_FIRST_TEMPLATE',
                                    :to, :subject, 'Body', 'PENDING', NOW(), NOW(), 'test')
                            """)
                    .setParameter("uuid", pendingEmailUuid)
                    .setParameter("candidate", idleCandidateUuid)
                    .setParameter("to", marker + "@example.com")
                    .setParameter("subject", marker + " subject").executeUpdate();

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_pending_emails WHERE uuid = :u")
                    .setParameter("u", pendingEmailUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid = :u")
                    .setParameter("u", referralUuid).executeUpdate();
            // Before cleanupRecruitmentRows: team.practice_uuid is an FK onto
            // the practice that call drops.
            em.createNativeQuery("DELETE FROM team WHERE uuid = :t")
                    .setParameter("t", teamUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(idleCandidateUuid, partnerCandidateUuid, hiredCandidateUuid),
                    List.of(teamPositionUuid, partnerPositionUuid),
                    List.of(recruiterUser, teamleadUser, wideTeamleadUser, practiceLeadUser,
                            interviewerUser, employeeUser, circleOwnerUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    private Response landingFor(String userUuid) {
        return given().header("X-Requested-By", userUuid)
                .when().get("/recruitment/landing")
                .then().statusCode(200)
                .extract().response();
    }

    /** The landing as the viewer would see it after asking for a scope. */
    private Response landingFor(String userUuid, String scope) {
        return given().header("X-Requested-By", userUuid)
                .queryParam("scope", scope)
                .when().get("/recruitment/landing")
                .then().statusCode(200)
                .extract().response();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasks(Response response) {
        return response.jsonPath().getList("tasks");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pipelines(Response response) {
        return response.jsonPath().getList("pipelines");
    }

    private static boolean hasTask(List<Map<String, Object>> tasks, String type, String key,
                                   String value) {
        return tasks.stream().anyMatch(t -> type.equals(t.get("type"))
                && (key == null || value.equals(t.get(key))));
    }

    private static boolean hasPipeline(List<Map<String, Object>> pipelines, String positionUuid) {
        return pipelines.stream().anyMatch(p -> positionUuid.equals(p.get("positionUuid")));
    }

    private static Map<String, Object> pipeline(List<Map<String, Object>> pipelines,
                                                String positionUuid) {
        return pipelines.stream()
                .filter(p -> positionUuid.equals(p.get("positionUuid")))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no pipeline row for " + positionUuid));
    }

    // ---- Role fixtures (spec §7.2 matrix) --------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void recruiter_seesTheWorld_minusPartnerCirclesSheIsNotIn() {
        Response response = landingFor(recruiterUser);

        assertEquals("RECRUITER", response.jsonPath().getString("viewerShape"));
        List<Map<String, Object>> tasks = tasks(response);
        assertTrue(hasTask(tasks, "IDLE_CANDIDATE", "applicationUuid", idleApplicationUuid),
                "the idle candidate is a recruiter task");
        assertTrue(hasTask(tasks, "REFERRAL_TO_TRIAGE", null, null),
                "the triage queue aggregate row is present");
        assertTrue(hasTask(tasks, "EMAIL_REVIEW", null, null),
                "the email review aggregate row is present (P15 carry-over absorbed)");
        // Partner hard filter: no partner task, pipeline or feed row.
        assertFalse(hasTask(tasks, "IDLE_CANDIDATE", "applicationUuid", partnerApplicationUuid),
                "partner-track task invisible outside the circle");
        List<Map<String, Object>> pipelines = pipelines(response);
        assertTrue(hasPipeline(pipelines, teamPositionUuid));
        assertFalse(hasPipeline(pipelines, partnerPositionUuid),
                "partner pipeline invisible outside the circle");
        List<Map<String, Object>> activity = response.jsonPath().getList("activity");
        assertTrue(activity.stream().noneMatch(a ->
                        partnerCandidateUuid.equals(a.get("candidateUuid"))),
                "CIRCLE events never reach a non-circle feed");
        assertTrue(response.jsonPath().getInt("kpis.openTasks") >= 3);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamlead_involvedShape_ownPositionTasksOnly() {
        Response response = landingFor(teamleadUser);

        assertEquals("INVOLVED", response.jsonPath().getString("viewerShape"));
        List<Map<String, Object>> tasks = tasks(response);
        assertTrue(hasTask(tasks, "IDLE_CANDIDATE", "applicationUuid", idleApplicationUuid),
                "team lead owns the decision on their team's position — the"
                        + " led-TEAM route survives the 2026-08-23 redesign"
                        + " (only the practice hop is gone, decision 11)");
        assertFalse(hasTask(tasks, "PENDING_DECISION", "interviewUuid", interviewUuid),
                "no decision task before the debrief is ready (scorecard still missing)");
        assertFalse(hasTask(tasks, "REFERRAL_TO_TRIAGE", null, null),
                "this user holds no role at all — the Inbox is role-gated"
                        + " (decision 12: recruiter tier + TEAMLEAD)");
        assertFalse(hasTask(tasks, "EMAIL_REVIEW", null, null));
        List<Map<String, Object>> pipelines = pipelines(response);
        assertFalse(hasPipeline(pipelines, teamPositionUuid),
                "decision 11: the led team's PRACTICE no longer makes its"
                        + " positions 'yours' — the OWN card narrows to named"
                        + " ownership and circle invitations");
        assertFalse(hasPipeline(pipelines, partnerPositionUuid));
    }

    // ---- Hired candidates are out of every "open" count -------------------------

    /**
     * The seed hires someone onto {@code teamPositionUuid} (stage HIRED,
     * terminal NULL — what {@code markHired} leaves) and backdates them into
     * the idle window. The board must not count them anywhere: the position
     * carries exactly the one candidate still being processed.
     * <p>
     * The task rows already defended themselves with {@code applicationInPlay};
     * these counts did not, which is what this pins.
     */
    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hiredCandidate_countsInNoPipelineNumber() {
        Response response = landingFor(recruiterUser);
        Map<String, Object> row = pipeline(pipelines(response), teamPositionUuid);

        assertEquals(1, row.get("openCount"),
                "the hire is no longer open on this position");
        assertEquals(1, row.get("idleCount"),
                "and does not join the idle bucket either, backdated or not");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stageCounts = (List<Map<String, Object>>) row.get("stageCounts");
        int staged = stageCounts.stream()
                .mapToInt(s -> ((Number) s.get("count")).intValue())
                .sum();
        assertEquals(1, staged, "stage counts sum to the one candidate in play");
        assertTrue(stageCounts.stream()
                        .noneMatch(s -> "HIRED".equals(s.get("stage"))
                                && ((Number) s.get("count")).intValue() > 0),
                "no populated HIRED column on a pipeline board");
    }

    /** The hire must not show up as a task either — decision or idle. */
    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hiredCandidate_raisesNoTask() {
        List<Map<String, Object>> tasks = tasks(landingFor(recruiterUser));
        assertFalse(hasTask(tasks, "IDLE_CANDIDATE", "candidateUuid", hiredCandidateUuid),
                "a hired candidate is not an idle candidate");
        assertFalse(hasTask(tasks, "PENDING_DECISION", "candidateUuid", hiredCandidateUuid),
                "nor is there a decision left to make about them");
    }

    // ---- "Your pipelines" scope (2026-08-11) ------------------------------------
    // The card leads with the viewer's OWN positions. Before this, anyone
    // holding TEAMLEAD led with every non-partner opening in the company —
    // in production, ten rows across six practices for a team lead who owned
    // none of them.

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamleadRole_withoutInvolvement_getsAnEmptyCard_notAnEmptyPage() {
        Response response = landingFor(wideTeamleadUser);

        // Still INVOLVED — read access is untouched, so the client does NOT
        // redirect them to /recruitment/refer. That distinction is the whole
        // point of narrowing the card rather than the visibility filter.
        assertEquals("INVOLVED", response.jsonPath().getString("viewerShape"));
        assertTrue(pipelines(response).isEmpty(),
                "owns nothing, leads nothing, invited to nothing");
        assertEquals("OWN", response.jsonPath().getString("pipelineScope"));
        assertTrue(response.jsonPath().getBoolean("pipelineScopeSelectable"),
                "and is offered the way out");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void kpisStayCompanyWide_evenWhileTheCardIsNarrow() {
        Response response = landingFor(wideTeamleadUser);

        // Product decision: the numbers answer "how is hiring going", the card
        // answers "what is mine". They are allowed to disagree — the KPI
        // subtitles say so.
        assertTrue(pipelines(response).isEmpty());
        assertTrue(response.jsonPath().getInt("kpis.openPositions") >= 1,
                "the KPI still counts the position they can read");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void scopeAll_widensTheCardBackToEverythingTheyMayRead() {
        Response response = landingFor(wideTeamleadUser, "ALL");

        assertEquals("ALL", response.jsonPath().getString("pipelineScope"));
        assertTrue(hasPipeline(pipelines(response), teamPositionUuid),
                "the position they read but do not own");
        assertFalse(hasPipeline(pipelines(response), partnerPositionUuid),
                "ALL never reaches past the partner circle — it is a display "
                        + "choice, not an authorization one");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamlead_ledTeamsPracticeNoLongerFeedsTheOwnCard() {
        // Reversed by decision 11 (2026-08-23): this user leads a TEAM whose
        // practice the position is on, and until the redesign that hop made
        // the position "theirs". Practice hops are gone from recruitment —
        // the OWN card narrows to named ownership and circle invitations,
        // while the led-team route still lets them ACT on the position
        // (asserted by teamlead_involvedShape_ownPositionTasksOnly).
        Response response = landingFor(teamleadUser);

        assertEquals("OWN", response.jsonPath().getString("pipelineScope"));
        assertFalse(hasPipeline(pipelines(response), teamPositionUuid),
                "the led team's practice no longer makes its openings theirs");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void recruiterTier_isNeverNarrowed_andIsNotOfferedTheChoice() {
        Response response = landingFor(recruiterUser);

        assertEquals("ALL", response.jsonPath().getString("pipelineScope"));
        assertFalse(response.jsonPath().getBoolean("pipelineScopeSelectable"),
                "the world is their job (spec §6.1) — no toggle to offer");
        assertTrue(hasPipeline(pipelines(response), teamPositionUuid));
    }

    /**
     * Reversed AGAIN on 2026-08-23 (decision 11), and this time removed: a
     * {@code practice_lead} row grants no recruitment right at all any more.
     * The 2026-08-12 grant this test used to pin was the route that let two
     * people with no recruitment role hold decision rights over eleven open
     * positions between them — rights now come from roles only, so a
     * role-less practice lead lands exactly where any other employee does.
     */
    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void practiceLeadRow_grantsNoLandingAtAll() {
        Response response = landingFor(practiceLeadUser);

        assertEquals("EMPLOYEE", response.jsonPath().getString("viewerShape"));
        assertTrue(pipelines(response).isEmpty(),
                "running a practice reveals no pipelines (decision 11)");
        assertTrue(tasks(response).isEmpty(),
                "and produces no decision tasks");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void interviewer_scorecardTasksOnly_noPipelinesNoFeed() {
        Response response = landingFor(interviewerUser);

        assertEquals("INTERVIEWER", response.jsonPath().getString("viewerShape"));
        List<Map<String, Object>> tasks = tasks(response);
        assertTrue(hasTask(tasks, "OVERDUE_SCORECARD", "interviewUuid", interviewUuid),
                "the overdue round-1 scorecard is the interviewer's task");
        assertTrue(pipelines(response).isEmpty(),
                "an interviewer sees interviews + scorecards only (spec §6.1)");
        assertTrue(response.jsonPath().getList("activity").isEmpty());
        assertTrue(response.jsonPath().getInt("kpis.openTasks") >= 1);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void circleOwner_seesThePartnerSlice() {
        Response response = landingFor(circleOwnerUser);

        assertEquals("INVOLVED", response.jsonPath().getString("viewerShape"));
        assertTrue(hasPipeline(pipelines(response), partnerPositionUuid),
                "circle membership reveals the partner pipeline");
        assertTrue(hasTask(tasks(response), "IDLE_CANDIDATE", "applicationUuid",
                        partnerApplicationUuid),
                "circle OWNER owns the partner decision tasks");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void employee_emptyShape_clientRedirectsToRefer() {
        Response response = landingFor(employeeUser);

        assertEquals("EMPLOYEE", response.jsonPath().getString("viewerShape"));
        assertTrue(tasks(response).isEmpty());
        assertTrue(pipelines(response).isEmpty());
        assertTrue(response.jsonPath().getList("activity").isEmpty());
        assertEquals(0, response.jsonPath().getInt("kpis.openTasks"));
    }

    // ---- Overdue scorecard task details -----------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void overdueScorecard_disappearsAfterSubmission() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertScorecard(em, UUID.randomUUID().toString(),
                        interviewUuid, interviewerUser, "YES"));

        Response response = landingFor(interviewerUser);

        assertFalse(hasTask(tasks(response), "OVERDUE_SCORECARD", "interviewUuid", interviewUuid),
                "a submitted scorecard is no longer a task");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void debriefReady_becomesPendingDecisionForTheTeamlead() {
        String scorecardUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_applications SET stage = 'INTERVIEW_1' "
                            + "WHERE uuid = :uuid")
                    .setParameter("uuid", idleApplicationUuid).executeUpdate();
            P8ProfileFixtures.insertScorecard(em, scorecardUuid, interviewUuid,
                    interviewerUser, "YES");
        });

        Response response = landingFor(teamleadUser);

        assertTrue(hasTask(tasks(response), "PENDING_DECISION", "interviewUuid", interviewUuid),
                "all scorecards in + unactioned = a decision task for the owner");
    }

    // ---- Flag gate ----------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void flagOff_nonAdminScope_uniform404() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false"));

        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/landing")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "admin:*"})
    void flagOff_adminScope_bypassesForDarkTesting() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false"));

        Response response = landingFor(recruiterUser);
        assertNotNull(response.jsonPath().getString("viewerShape"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void missingRequestedBy_is400() {
        given().when().get("/recruitment/landing")
                .then().statusCode(400);
    }
}
