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
 *   <li>teamlead involvement: the position's current team leader reads,
 *       a teamlead of another team answers 404;</li>
 *   <li>practice lead reads candidates on non-partner positions of their
 *       practice; never partner content;</li>
 *   <li>plain employee answers 404 everywhere;</li>
 *   <li>hired-file restriction: once status is HIRED, access narrows to
 *       HR/CXO/TECHPARTNER/DPO (+ADMIN) — the involved teamlead loses
 *       access;</li>
 *   <li>zero-application candidates stay visible to the profile-read tier
 *       but grant no involvement access.</li>
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

    private String teamPosition;
    private String partnerPosition;

    private String normalCandidate;
    private String partnerOnlyCandidate;
    private String noAppCandidate;
    private String hiredCandidate;

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
        teamPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        normalCandidate = UUID.randomUUID().toString();
        partnerOnlyCandidate = UUID.randomUUID().toString();
        noAppCandidate = UUID.randomUUID().toString();
        hiredCandidate = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertUser(em, circleHr, "Cirkel", "Recruiter");
            P8ProfileFixtures.insertUser(em, involvedTeamlead, "Tim", "Teamlead");
            P8ProfileFixtures.insertUser(em, nonOwnerTeamlead, "Nia", "Otherlead");
            P8ProfileFixtures.insertUser(em, practiceLead, "Pia", "Lead");
            P8ProfileFixtures.insertUser(em, dpoUser, "Dorte", "Dpo");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
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

            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    normalCandidate, teamPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    partnerOnlyCandidate, partnerPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
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
                            practiceLead, dpoUser, plainUser),
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
    void teamleadOfThePositionsTeam_reads_otherTeamleadDoesNot() {
        assertAllSurfaces(involvedTeamlead, normalCandidate, 200);
        assertAllSurfaces(nonOwnerTeamlead, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void practiceLead_readsPracticeCandidates_neverPartnerContent() {
        assertAllSurfaces(practiceLead, normalCandidate, 200);
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
        assertAllSurfaces(involvedTeamlead, noAppCandidate, 404);
    }

    // ---- Helpers ------------------------------------------------------------------

    private void assertAllSurfaces(String viewer, String candidateUuid, int expectedStatus) {
        for (String surface : SURFACES) {
            given().header("X-Requested-By", viewer)
                    .when().get("/recruitment/candidates/{uuid}/" + surface, candidateUuid)
                    .then().statusCode(expectedStatus);
        }
    }
}
