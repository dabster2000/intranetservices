package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;

/**
 * P8 DoD: the five-viewer profile-access matrix, end-to-end through the
 * four read surfaces (events / answers / documents / consents) with real
 * {@code roles} / {@code teamroles} / {@code practice_lead} fixture rows
 * resolved via {@code X-Requested-By}:
 * <ul>
 *   <li>recruiter (HR) reads everything except partner-track-only
 *       candidates outside their circles (404, never 403);</li>
 *   <li>circle member and ADMIN read the partner-track-only candidate;</li>
 *   <li>the TEAMLEAD role reads every non-partner candidate — leading the
 *       position's team is no longer required (go-live decision D3);</li>
 *   <li>practice lead reads candidates on non-partner positions of their
 *       practice; never partner content;</li>
 *   <li>plain employee answers 404 everywhere;</li>
 *   <li>hired-file restriction: once status is HIRED, access narrows to
 *       HR/RECRUITMENT/DPO (+ADMIN) — the teamlead loses access;</li>
 *   <li>zero-application candidates stay visible to the profile-read tier
 *       but grant no involvement access;</li>
 *   <li>interviewer persona: an assigned interviewer is NOT admitted to
 *       the profile surfaces any more (go-live decision D10) — they read
 *       the restricted brief instead. Kept here because the old rule read
 *       exactly their assigned candidate's
 *       surfaces (incl. answers — "answers only for assigned candidates"),
 *       nothing else; a cancelled assignment grants nothing; the grant
 *       reaches partner-track candidates (explicit assignment by a
 *       circle-authorized decision maker) and dies at HIRED.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentCandidateProfileAuthzApiTest {

    private static final String[] SURFACES = {"events", "answers", "documents", "consents"};

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String teamA;
    private String teamB;

    private String hrUser;
    private String adminUser;
    private String circleHr;
    private String involvedTeamlead;
    private String nonOwnerTeamlead;
    private String practiceLead;
    private String dpoUser;
    private String plainUser;
    private String interviewerUser;

    private String teamPosition;
    private String partnerPosition;

    private String normalCandidate;
    private String partnerOnlyCandidate;
    private String noAppCandidate;
    private String hiredCandidate;

    private String normalApplication;
    private String partnerApplication;
    private String hiredApplication;

    private String previousFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamA = UUID.randomUUID().toString();
        teamB = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        circleHr = UUID.randomUUID().toString();
        involvedTeamlead = UUID.randomUUID().toString();
        nonOwnerTeamlead = UUID.randomUUID().toString();
        practiceLead = UUID.randomUUID().toString();
        dpoUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        interviewerUser = UUID.randomUUID().toString();
        teamPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        normalCandidate = UUID.randomUUID().toString();
        partnerOnlyCandidate = UUID.randomUUID().toString();
        noAppCandidate = UUID.randomUUID().toString();
        hiredCandidate = UUID.randomUUID().toString();
        normalApplication = UUID.randomUUID().toString();
        partnerApplication = UUID.randomUUID().toString();
        hiredApplication = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertUser(em, circleHr, "Cirkel", "Recruiter");
            P8ProfileFixtures.insertUser(em, involvedTeamlead, "Tim", "Teamlead");
            P8ProfileFixtures.insertUser(em, nonOwnerTeamlead, "Nia", "Otherlead");
            P8ProfileFixtures.insertUser(em, practiceLead, "Pia", "Lead");
            P8ProfileFixtures.insertUser(em, dpoUser, "Dorte", "Dpo");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertUser(em, interviewerUser, "Iben", "Interviewer");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");
            P8ProfileFixtures.insertRole(em, circleHr, "HR");
            P8ProfileFixtures.insertRole(em, involvedTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, nonOwnerTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, dpoUser, "DPO");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPracticeLead(em, practiceLead, practiceUuid);
            P8ProfileFixtures.insertTeamLeader(em, involvedTeamlead, teamA);
            P8ProfileFixtures.insertTeamLeader(em, nonOwnerTeamlead, teamB);

            P8ProfileFixtures.insertPosition(em, teamPosition, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, teamA, null);
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertCircleMember(em, partnerPosition, circleHr);

            P8ProfileFixtures.insertCandidate(em, normalCandidate,
                    "PII_SENTINEL Anna", "PII_SENTINEL Ager", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, partnerOnlyCandidate,
                    "PII_SENTINEL Gro", "PII_SENTINEL Gram", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, noAppCandidate,
                    "PII_SENTINEL Nul", "PII_SENTINEL Nyholm", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, hiredCandidate,
                    "PII_SENTINEL Hilda", "PII_SENTINEL Hyre", "HIRED", null, null, hrUser);

            P8ProfileFixtures.insertOpenApplication(em, normalApplication,
                    normalCandidate, teamPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, partnerApplication,
                    partnerOnlyCandidate, partnerPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, hiredApplication,
                    hiredCandidate, teamPosition, "HIRED");

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(normalCandidate, partnerOnlyCandidate, noAppCandidate, hiredCandidate),
                    List.of(teamPosition, partnerPosition),
                    List.of(hrUser, adminUser, circleHr, involvedTeamlead, nonOwnerTeamlead,
                            practiceLead, dpoUser, plainUser, interviewerUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    // ---- Recruiter tier ---------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void recruiter_readsNormalCandidate_onAllFourSurfaces() {
        assertAllSurfaces(hrUser, normalCandidate, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerTrackOnlyCandidate_answers404ForNonCircleRecruiter_onEverySurface() {
        assertAllSurfaces(hrUser, partnerOnlyCandidate, 404);
        // The download leg fails the same way — profile access runs first.
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/documents/{f}",
                        partnerOnlyCandidate, UUID.randomUUID().toString())
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerTrackOnlyCandidate_visibleToCircleMemberAndAdmin() {
        assertAllSurfaces(circleHr, partnerOnlyCandidate, 200);
        assertAllSurfaces(adminUser, partnerOnlyCandidate, 200);
    }

    // ---- Involvement tier -------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamleadRole_readsEveryNonPartnerCandidate_regardlessOfTeam() {
        // Go-live decision D3: the TEAMLEAD role reads the whole
        // non-partner population. Leading the position's team no longer
        // gates reading — it gates DECIDING (canDecideOnApplication).
        assertAllSurfaces(involvedTeamlead, normalCandidate, 200);
        assertAllSurfaces(nonOwnerTeamlead, normalCandidate, 200);
        // The partner circle stays a hard filter for both.
        assertAllSurfaces(involvedTeamlead, partnerOnlyCandidate, 404);
        assertAllSurfaces(nonOwnerTeamlead, partnerOnlyCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void practiceLeadRow_readsNoCandidateAtAll() {
        // Decision 11 (2026-08-23): the practice-lead involvement route is
        // gone from the candidate profile too — a role-less practice lead
        // answers 404 on every surface, like any other uninvolved employee.
        assertAllSurfaces(practiceLead, normalCandidate, 404);
        assertAllSurfaces(practiceLead, partnerOnlyCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void plainEmployee_answers404Everywhere() {
        assertAllSurfaces(plainUser, normalCandidate, 404);
        assertAllSurfaces(plainUser, noAppCandidate, 404);
    }

    // ---- Hired-file restriction -------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hiredFile_narrowsToRecruiterAdminDpo_involvedTeamleadLosesAccess() {
        assertAllSurfaces(involvedTeamlead, hiredCandidate, 404);
        assertAllSurfaces(hrUser, hiredCandidate, 200);
        assertAllSurfaces(dpoUser, hiredCandidate, 200);
        assertAllSurfaces(adminUser, hiredCandidate, 200);
        assertAllSurfaces(plainUser, hiredCandidate, 404);
    }

    // ---- Zero-application candidates ----------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void noApplicationCandidate_visibleToProfileTier_notThroughInvolvement() {
        assertAllSurfaces(hrUser, noAppCandidate, 200);
        // TEAMLEAD is in the profile-read tier since go-live, so it reaches
        // this row by role; the plain employee still cannot.
        assertAllSurfaces(involvedTeamlead, noAppCandidate, 200);
        assertAllSurfaces(plainUser, noAppCandidate, 404);
    }

    // ---- Interviewer persona (P11 — the fifth user of the matrix) -----------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void interviewer_getsTheBriefOnly_neverTheProfileSurfaces() {
        assignInterviewer(normalApplication, "SCHEDULED");
        // Go-live decision D10: the assignment no longer opens the profile
        // — no timeline, no consents, no documents list.
        assertAllSurfaces(interviewerUser, normalCandidate, 404);
        // It opens exactly one thing: the restricted brief.
        assertBrief(interviewerUser, normalCandidate, 200);
        // And only for the assigned candidate.
        assertBrief(interviewerUser, noAppCandidate, 404);
        assertBrief(interviewerUser, hiredCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void interviewer_withoutAssignment_orCancelledAssignment_reads404() {
        assertBrief(interviewerUser, normalCandidate, 404);
        assignInterviewer(normalApplication, "CANCELLED");
        assertBrief(interviewerUser, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void interviewer_briefReachesPartnerTrack_butDiesWhenTheCandidateLeavesActive() {
        // Explicit assignment by a circle-authorized decision maker admits
        // the interviewer to the partner-track candidate's BRIEF (kit
        // access) — the profile stays shut even here.
        assignInterviewer(partnerApplication, "SCHEDULED");
        assertBrief(interviewerUser, partnerOnlyCandidate, 200);
        assertAllSurfaces(interviewerUser, partnerOnlyCandidate, 404);

        // The window closes with the candidate's status (D12): a HIRED
        // candidate is no longer ACTIVE, so the assignment grants nothing.
        assignInterviewer(hiredApplication, "HELD");
        assertBrief(interviewerUser, hiredCandidate, 404);
        assertAllSurfaces(interviewerUser, hiredCandidate, 404);
    }

    private void assignInterviewer(String applicationUuid, String status) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertInterview(em, UUID.randomUUID().toString(),
                        applicationUuid, "ROUND", 1,
                        "[\"" + interviewerUser + "\"]", status));
    }

    // ---- Helpers ------------------------------------------------------------------

    /** The restricted brief — the interviewer/circle-member surface. */
    private void assertBrief(String viewer, String candidateUuid, int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .when().get("/recruitment/candidates/{uuid}/brief", candidateUuid)
                .then().statusCode(expectedStatus);
    }

    private void assertAllSurfaces(String viewer, String candidateUuid, int expectedStatus) {
        for (String surface : SURFACES) {
            given().header("X-Requested-By", viewer)
                    .when().get("/recruitment/candidates/{uuid}/" + surface, candidateUuid)
                    .then().statusCode(expectedStatus);
        }
    }
}
