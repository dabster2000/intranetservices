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
    private String interviewUuid;
    private String referralUuid;
    private String pendingEmailUuid;

    private String previousFlag;

    @BeforeEach
    void seed() {
        marker = "LndT" + UUID.randomUUID().toString().substring(0, 8);
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
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

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPracticeLead(em, practiceLeadUser, practiceUuid);
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
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(idleCandidateUuid, partnerCandidateUuid),
                    List.of(teamPositionUuid, partnerPositionUuid),
                    List.of(recruiterUser, teamleadUser, practiceLeadUser, interviewerUser,
                            employeeUser, circleOwnerUser),
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
                "team lead owns the decision on their team's position");
        assertFalse(hasTask(tasks, "PENDING_DECISION", "interviewUuid", interviewUuid),
                "no decision task before the debrief is ready (scorecard still missing)");
        assertFalse(hasTask(tasks, "REFERRAL_TO_TRIAGE", null, null),
                "intake queues are recruiter-tier only");
        assertFalse(hasTask(tasks, "EMAIL_REVIEW", null, null));
        List<Map<String, Object>> pipelines = pipelines(response);
        assertTrue(hasPipeline(pipelines, teamPositionUuid));
        assertFalse(hasPipeline(pipelines, partnerPositionUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void practiceLead_readsPipelines_ownsNoDecisionTasks() {
        Response response = landingFor(practiceLeadUser);

        assertEquals("INVOLVED", response.jsonPath().getString("viewerShape"));
        assertTrue(hasPipeline(pipelines(response), teamPositionUuid),
                "practice lead reads the practice's non-partner pipelines");
        List<Map<String, Object>> tasks = tasks(response);
        assertFalse(hasTask(tasks, "IDLE_CANDIDATE", "applicationUuid", idleApplicationUuid),
                "read access grants no decision-owned tasks (spec §7.2)");
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
