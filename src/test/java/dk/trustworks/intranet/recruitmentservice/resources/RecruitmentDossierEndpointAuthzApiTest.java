package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.DOSSIER_FLAG;
import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;

/**
 * Object-level authorization for the pre-ATS dossier endpoint family
 * (security finding LOW-1). The family — {@code GET /candidates/{uuid}},
 * the {@code /dossier/**} reads/writes, {@code send-review},
 * {@code send-signature}, {@code convert}, … — previously trusted the
 * {@code recruitment:write}/{@code read} scope alone; each endpoint now
 * runs {@code RecruitmentResource.requireVisibleCandidate}, funnelling
 * through {@link dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility#canReadCandidateProfile}
 * — the same rule as the P8 profile reads.
 *
 * <p>Two representative endpoints stand in for the whole family (they share
 * one gate helper): {@code GET /recruitment/candidates/{uuid}} (the
 * candidate-data read, 200 when visible) and
 * {@code GET /recruitment/candidates/{uuid}/dossier/revisions} (a dossier
 * subpath). Invisible candidates answer 404, never 403 — existence must not
 * leak. Uses {@code X-Requested-By} to carry the acting user, and the
 * {@code recruitment.dossier.enabled} flag (this family gates on the dossier
 * flag, not the pipeline flag).</p>
 */
@QuarkusTest
class RecruitmentDossierEndpointAuthzApiTest {

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String otherPracticeUuid;
    private String teamA;
    private String teamB;

    private String hrUser;
    private String techPartnerUser;
    private String teamleadRoleUser;
    private String owningTeamlead;
    private String circleHr;
    private String adminUser;
    private String recruitmentUser;
    private String plainUser;
    private String assistantUser;
    private String assistantAndTeamleadUser;
    private String assistantWithoutPracticeUser;

    private String teamPosition;
    private String outOfPracticePosition;
    private String noPracticeOwnedPosition;
    private String partnerPosition;

    private String normalCandidate;
    private String outOfPracticeCandidate;
    private String partnerOnlyCandidate;
    private String legacyDossierCandidate;
    private String targetCompanyUuid;
    private String dossierTemplateUuid;
    private String normalApplication;
    private String outOfPracticeApplication;
    private String candidateUploadToken;
    private String foreignCandidateUploadToken;
    private String employeeUploadToken;

    private String previousFlag;
    private String previousPipelineFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        otherPracticeUuid = UUID.randomUUID().toString();
        teamA = UUID.randomUUID().toString();
        teamB = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        techPartnerUser = UUID.randomUUID().toString();
        teamleadRoleUser = UUID.randomUUID().toString();
        owningTeamlead = UUID.randomUUID().toString();
        circleHr = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        recruitmentUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        assistantUser = UUID.randomUUID().toString();
        assistantAndTeamleadUser = UUID.randomUUID().toString();
        assistantWithoutPracticeUser = UUID.randomUUID().toString();
        teamPosition = UUID.randomUUID().toString();
        outOfPracticePosition = UUID.randomUUID().toString();
        noPracticeOwnedPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        normalCandidate = UUID.randomUUID().toString();
        outOfPracticeCandidate = UUID.randomUUID().toString();
        partnerOnlyCandidate = UUID.randomUUID().toString();
        legacyDossierCandidate = UUID.randomUUID().toString();
        targetCompanyUuid = UUID.randomUUID().toString();
        dossierTemplateUuid = UUID.randomUUID().toString();
        normalApplication = UUID.randomUUID().toString();
        outOfPracticeApplication = UUID.randomUUID().toString();
        candidateUploadToken = UUID.randomUUID().toString();
        foreignCandidateUploadToken = UUID.randomUUID().toString();
        employeeUploadToken = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, techPartnerUser, "Tino", "Techpartner");
            P8ProfileFixtures.insertUser(em, teamleadRoleUser, "Tilde", "Teamlead");
            P8ProfileFixtures.insertUser(em, owningTeamlead, "Ole", "Owner");
            P8ProfileFixtures.insertUser(em, circleHr, "Cirkel", "Recruiter");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertUser(em, recruitmentUser, "Rene", "Recruitment");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertUser(em, assistantUser, "Rita", "Assistant");
            P8ProfileFixtures.insertUser(em, assistantAndTeamleadUser, "Bo", "Both");
            P8ProfileFixtures.insertUser(em, assistantWithoutPracticeUser,
                    "Mia", "Missing Practice");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertRole(em, techPartnerUser, "TECHPARTNER");
            P8ProfileFixtures.insertRole(em, teamleadRoleUser, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, owningTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, circleHr, "HR");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");
            P8ProfileFixtures.insertRole(em, recruitmentUser, "RECRUITMENT");
            P8ProfileFixtures.insertRole(em, assistantUser, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantAndTeamleadUser, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantAndTeamleadUser, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantWithoutPracticeUser, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPractice(em, otherPracticeUuid);
            setUserPractice(assistantUser, practiceUuid);
            setUserPractice(assistantAndTeamleadUser, practiceUuid);

            P8ProfileFixtures.insertPosition(em, teamPosition, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, teamA, owningTeamlead);
            // Both owner and current-leader involvement are deliberately
            // present: assistant-only must still be scoped exclusively by
            // practice, never by either generic fallback.
            P8ProfileFixtures.insertPosition(em, outOfPracticePosition, "Other practice",
                    "PRACTICE_TEAM", otherPracticeUuid, teamB, assistantUser);
            P8ProfileFixtures.insertTeamLeader(em, assistantUser, teamB);
            P8ProfileFixtures.insertPosition(em, noPracticeOwnedPosition, "Missing practice owner",
                    "PRACTICE_TEAM", practiceUuid, teamB, assistantWithoutPracticeUser);
            P8ProfileFixtures.insertTeamLeader(em, assistantWithoutPracticeUser, teamB);
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertCircleMember(em, partnerPosition, circleHr);

            P8ProfileFixtures.insertCandidate(em, normalCandidate,
                    "PII_SENTINEL Anna", "PII_SENTINEL Ager", "ACTIVE", null, null, hrUser);
            setDossierEraCandidateMetadata(normalCandidate);
            P8ProfileFixtures.insertCandidate(em, outOfPracticeCandidate,
                    "PII_SENTINEL Oda", "PII_SENTINEL Other", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, partnerOnlyCandidate,
                    "PII_SENTINEL Gro", "PII_SENTINEL Gram", "ACTIVE", null, null, hrUser);
            // Legacy dossier-only candidate: no application at all (pre-ATS flow).
            P8ProfileFixtures.insertCandidate(em, legacyDossierCandidate,
                    "PII_SENTINEL Lars", "PII_SENTINEL Legacy", "ACTIVE", null, null, hrUser);

            P8ProfileFixtures.insertOpenApplication(em, normalApplication,
                    normalCandidate, teamPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, outOfPracticeApplication,
                    outOfPracticeCandidate, outOfPracticePosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    partnerOnlyCandidate, partnerPosition, "SCREENING");

            // A dossier for the normal candidate so the /dossier/revisions
            // subpath returns 200 for a visible viewer (distinguishing a
            // gate 404 from a dossier-missing 404). template_uuid is a soft FK.
            insertDossier(normalCandidate);
            insertOnboardingToken(candidateUploadToken, normalCandidate, null);
            insertOnboardingToken(foreignCandidateUploadToken, outOfPracticeCandidate, null);
            insertOnboardingToken(employeeUploadToken, null, plainUser);

            previousFlag = P8ProfileFixtures.setFlag(em, DOSSIER_FLAG, "true");
            previousPipelineFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            DELETE FROM onboarding_upload_submissions
                             WHERE token_uuid IN (
                                   SELECT uuid FROM onboarding_upload_tokens
                                    WHERE candidate_uuid IN :c OR user_uuid IN :u)
                            """)
                    .setParameter("c", candidateUuids())
                    .setParameter("u", List.of(hrUser, techPartnerUser, teamleadRoleUser,
                            owningTeamlead, circleHr, adminUser, recruitmentUser, plainUser,
                            assistantUser, assistantAndTeamleadUser,
                            assistantWithoutPracticeUser))
                    .executeUpdate();
            em.createNativeQuery("""
                            DELETE FROM onboarding_upload_tokens
                             WHERE candidate_uuid IN :c OR user_uuid IN :u
                            """)
                    .setParameter("c", candidateUuids())
                    .setParameter("u", List.of(hrUser, techPartnerUser, teamleadRoleUser,
                            owningTeamlead, circleHr, adminUser, recruitmentUser, plainUser,
                            assistantUser, assistantAndTeamleadUser,
                            assistantWithoutPracticeUser))
                    .executeUpdate();
            // candidate_dossiers.candidate_uuid is ON DELETE RESTRICT — clear
            // dossiers before the shared cleanup deletes the candidates.
            em.createNativeQuery("""
                            DELETE FROM candidate_dossier_revisions
                             WHERE dossier_uuid IN (
                                   SELECT uuid FROM candidate_dossiers WHERE candidate_uuid IN :c)
                            """)
                    .setParameter("c", candidateUuids())
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid IN :c")
                    .setParameter("c", candidateUuids())
                    .executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    candidateUuids(),
                    List.of(teamPosition, outOfPracticePosition,
                            noPracticeOwnedPosition, partnerPosition),
                    List.of(hrUser, techPartnerUser, teamleadRoleUser, owningTeamlead,
                            circleHr, adminUser, recruitmentUser, plainUser, assistantUser,
                            assistantAndTeamleadUser, assistantWithoutPracticeUser),
                    practiceUuid);
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", otherPracticeUuid)
                    .executeUpdate();
            P8ProfileFixtures.restoreFlag(em, DOSSIER_FLAG, previousFlag);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipelineFlag);
        });
    }

    // ---- Profile-read tier: the current production dossier audience ------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hr_readsPlainCandidate_ok() {
        getCandidate(hrUser, normalCandidate, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void techPartner_readsPlainCandidate_404() {
        // TECHPARTNER was removed from the recruitment module at go-live
        // (2026-08-10, decision D7): it is no longer in the profile-read
        // tier and holds no involvement, so the candidate is invisible —
        // 404, not 403, per the no-leak rule.
        getCandidate(techPartnerUser, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamlead_readsPlainCandidate_ok() {
        // TEAMLEAD joined the profile-read tier at go-live (D3): a team lead
        // reads the whole non-partner candidate population.
        getCandidate(teamleadRoleUser, normalCandidate, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void legacyDossierOnlyCandidate_zeroApplications_visibleToProfileTier() {
        // A pre-ATS dossier candidate has no applications and so is never
        // "partner-track-only" — it stays visible to the profile-read tier.
        getCandidate(hrUser, legacyDossierCandidate, 200);
        getCandidate(teamleadRoleUser, legacyDossierCandidate, 200);
    }

    // ---- Partner-track hard filter --------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hr_onPartnerTrackOnlyCandidateOutsideCircle_404() {
        getCandidate(hrUser, partnerOnlyCandidate, 404);
        getCandidate(teamleadRoleUser, partnerOnlyCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerTrackOnlyCandidate_visibleToCircleMemberAndAdmin() {
        getCandidate(circleHr, partnerOnlyCandidate, 200);
        getCandidate(adminUser, partnerOnlyCandidate, 200);
    }

    // ---- Involvement tier / no access -----------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void plainEmployee_404() {
        getCandidate(plainUser, normalCandidate, 404);
        getCandidate(plainUser, legacyDossierCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void missingRequestedByHeader_failsClosed_404() {
        given().when()
                .get("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void candidateListRequiresAValidRequestedByHeaderBeforeReturningPii() {
        given().when()
                .get("/recruitment/candidates")
                .then().statusCode(400);
        given().header("X-Requested-By", "not-a-uuid")
                .when().get("/recruitment/candidates")
                .then().statusCode(400);
        given().header("X-Requested-By", plainUser)
                .when().get("/recruitment/candidates")
                .then().statusCode(403);

        given().header("X-Requested-By", assistantUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(1));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantCandidateGridRequiresOwnPracticeApplication() {
        given().header("X-Requested-By", assistantUser)
                .queryParam("search", "PII_SENTINEL Lars")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(0));

        given().header("X-Requested-By", assistantWithoutPracticeUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(0));
    }

    // ---- Assistant practice scope is exclusive for non-partner recruitment -----

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantNamedOwnerAndCurrentLeader_cannotReadOutsidePractice() {
        given().header("X-Requested-By", assistantUser)
                .when().get("/recruitment/positions/{uuid}", outOfPracticePosition)
                .then().statusCode(404);
        getCandidate(assistantUser, outOfPracticeCandidate, 404);

        given().header("X-Requested-By", assistantUser)
                .queryParam("search", "PII_SENTINEL Oda")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(0));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantPositionList_excludesNamedOwnedOutOfPracticePosition() {
        given().header("X-Requested-By", assistantUser)
                .when().get("/recruitment/positions")
                .then().statusCode(200)
                .body("positions.uuid", org.hamcrest.Matchers.hasItem(teamPosition))
                .body("positions.uuid", org.hamcrest.Matchers.hasItem(noPracticeOwnedPosition))
                .body("positions.uuid", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(outOfPracticePosition)));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCannotMoveOutOfPracticeApplicationDespiteOwnershipAndLeadership() {
        moveToInterviewOne(assistantUser, outOfPracticeApplication, 404);
        moveToInterviewOne(assistantUser, normalApplication, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCanEditOwnNoteOnlyWhileCandidateRemainsInPractice() {
        String eventId = given().header("X-Requested-By", assistantUser)
                .contentType("application/json")
                .body("{\"text\":\"original note\",\"isPrivate\":false}")
                .when().post("/recruitment/candidates/{uuid}/notes", normalCandidate)
                .then().statusCode(201)
                .extract().path("eventId");

        editDiscussionNote(assistantUser, eventId, "same-practice correction", 200);

        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_positions
                                   SET practice_uuid = :practice
                                 WHERE uuid = :position
                                """)
                        .setParameter("practice", otherPracticeUuid)
                        .setParameter("position", teamPosition)
                        .executeUpdate());

        editDiscussionNote(assistantUser, eventId, "out-of-practice correction", 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantWithoutPractice_failsClosedDespiteOwnershipAndLeadership() {
        given().header("X-Requested-By", assistantWithoutPracticeUser)
                .when().get("/recruitment/positions/{uuid}", noPracticeOwnedPosition)
                .then().statusCode(404);
        given().header("X-Requested-By", assistantWithoutPracticeUser)
                .when().get("/recruitment/positions")
                .then().statusCode(200)
                .body("positions", org.hamcrest.Matchers.hasSize(0));
        getCandidate(assistantWithoutPracticeUser, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void simultaneousTeamleadRole_preservesBroaderOutOfPracticeAccess() {
        given().header("X-Requested-By", assistantAndTeamleadUser)
                .when().get("/recruitment/positions/{uuid}", outOfPracticePosition)
                .then().statusCode(200);
        getCandidate(assistantAndTeamleadUser, outOfPracticeCandidate, 200);
        moveToInterviewOne(assistantAndTeamleadUser, outOfPracticeApplication, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantInterviewResourcePinsArePracticeScopedAndPartnerClosed() {
        replaceInterviewResourcePins(assistantUser, teamPosition, 204);
        replaceInterviewResourcePins(assistantUser, outOfPracticePosition, 404);
        replaceInterviewResourcePins(assistantUser, partnerPosition, 404);
        replaceInterviewResourcePins(assistantWithoutPracticeUser,
                noPracticeOwnedPosition, 404);
        replaceInterviewResourcePins(circleHr, partnerPosition, 204);
    }

    // ---- The gate is wired into the dossier subpaths, not just getCandidate ----

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void dossierSubpath_appliesTheSameGate() {
        // Visible viewer + existing dossier → 200 (empty revision list).
        listRevisions(hrUser, normalCandidate, 200);
        // Invisible candidate → 404 by the gate, before the dossier lookup.
        listRevisions(hrUser, partnerOnlyCandidate, 404);
        listRevisions(plainUser, normalCandidate, 404);
    }

    // ---- Dossier access is narrower than profile access ------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamleadWithoutOwnership_readsProfileButNotDossier() {
        // The whole point of the dedicated dossier gate: a TEAMLEAD is in the
        // profile-read tier, so the candidate is visible — but the contract is
        // not theirs, and the dossier answers 404 rather than 403 so that
        // "there is a contract here" does not leak either.
        getCandidate(teamleadRoleUser, normalCandidate, 200);
        listRevisions(teamleadRoleUser, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void namedHiringOwner_readsTheDossier() {
        listRevisions(owningTeamlead, normalCandidate, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hrAndAdmin_readTheDossier() {
        listRevisions(hrUser, normalCandidate, 200);
        listRevisions(adminUser, normalCandidate, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void plainEmployee_getsNothing() {
        listRevisions(plainUser, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void namedHiringOwner_cannotWriteTheDossier() {
        // Readable, so the honest answer is 403 with a reason — a 404 here
        // would just look broken to someone who can see the dossier.
        given().header("X-Requested-By", owningTeamlead)
                .contentType("application/json")
                .body("{\"values\":{}}")
                .when().put("/recruitment/candidates/{uuid}/dossier", normalCandidate)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void teamleadWithoutOwnership_writeAnswers404NotForbidden() {
        // Cannot read it, so must not learn it exists by getting a 403.
        given().header("X-Requested-By", teamleadRoleUser)
                .contentType("application/json")
                .body("{\"values\":{}}")
                .when().put("/recruitment/candidates/{uuid}/dossier", normalCandidate)
                .then().statusCode(404);
    }

    // ---- Onboarding upload links are dossier / employee-document controls -----

    @Test
    @TestSecurity(user = "bff-client", roles = {
            "recruitment:read", "recruitment:write", "users:read"})
    void candidateUploadTokenUsesTheDossierReadAudience() {
        candidateTokenGet(assistantUser, normalCandidate, 404, null);
        candidateTokenGet(plainUser, normalCandidate, 404, null);
        candidateTokenGet(owningTeamlead, normalCandidate, 200, candidateUploadToken);
        candidateTokenGet(hrUser, normalCandidate, 200, candidateUploadToken);
        candidateTokenGet(adminUser, normalCandidate, 200, candidateUploadToken);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {
            "recruitment:read", "recruitment:write", "users:read"})
    void candidateUploadTokenMutationsRequireHrOrAdminAndRecheckTokenOwner() {
        createCandidateToken(assistantUser, normalCandidate, 404);
        createCandidateToken(plainUser, normalCandidate, 404);
        createCandidateToken(owningTeamlead, normalCandidate, 403);

        String replacement = createCandidateToken(hrUser, normalCandidate, 200);

        mutateToken(assistantUser, replacement, "PUT", candidateTokenBody(normalCandidate), 404);
        mutateToken(owningTeamlead, replacement, "PUT", candidateTokenBody(normalCandidate), 403);
        // Knowing a token id for a candidate in another practice must not
        // bypass the candidate-aware dossier gate.
        mutateToken(assistantUser, foreignCandidateUploadToken, "PUT",
                candidateTokenBody(outOfPracticeCandidate), 404);
        mutateToken(assistantUser, foreignCandidateUploadToken, "DELETE", null, 404);

        mutateToken(adminUser, replacement, "PUT", candidateTokenBody(normalCandidate), 200);
        mutateToken(adminUser, replacement, "DELETE", null, 204);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {
            "recruitment:read", "recruitment:write", "users:read"})
    void employeeUploadTokenRoutesAreHrAdminOnlyIncludingDirectTokenIds() {
        employeeTokenGet(assistantUser, plainUser, 404, null);
        employeeTokenGet(plainUser, plainUser, 404, null);
        employeeTokenGet(hrUser, plainUser, 200, employeeUploadToken);
        employeeTokenGet(adminUser, plainUser, 200, employeeUploadToken);

        createEmployeeToken(assistantUser, plainUser, 404);
        createEmployeeToken(plainUser, plainUser, 404);
        mutateToken(assistantUser, employeeUploadToken, "PUT",
                employeeTokenBody(plainUser), 404);
        mutateToken(plainUser, employeeUploadToken, "DELETE", null, 404);

        String replacement = createEmployeeToken(adminUser, plainUser, 200);
        mutateToken(hrUser, replacement, "PUT", employeeTokenBody(plainUser), 200);
        mutateToken(hrUser, replacement, "DELETE", null, 204);
    }

    // ---- Promotion repair is a dossier/employee-document mutation -------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCannotInvokePromotionRepairRoutes() {
        markCandidateConverted();
        promotionRepair(assistantUser, "redrive", 404);
        promotionRepair(assistantUser, "restore-staging", 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void readOnlyNamedTeamleadCannotInvokePromotionRepairRoutes() {
        markCandidateConverted();
        // The named hiring owner can read the dossier, so the canonical
        // write gate answers 403 rather than hiding it behind a 404.
        promotionRepair(owningTeamlead, "redrive", 403);
        promotionRepair(owningTeamlead, "restore-staging", 403);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void recruitmentRoleCannotInvokePromotionRepairRoutes() {
        markCandidateConverted();
        promotionRepair(recruitmentUser, "redrive", 404);
        promotionRepair(recruitmentUser, "restore-staging", 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void hrAndAdminCanInvokePromotionRepairRoutes() {
        markCandidateConverted();
        promotionRepair(hrUser, "redrive", 200);
        promotionRepair(hrUser, "restore-staging", 200);
        promotionRepair(adminUser, "redrive", 200);
        promotionRepair(adminUser, "restore-staging", 200);
    }

    // ---- Legacy candidate-level terminals enforce final-outcome rights ---------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCannotDeclineThroughLegacyCandidateRoute() {
        terminal(assistantUser, normalCandidate, "decline", 403, null);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCannotWithdrawThroughLegacyCandidateRoute() {
        terminal(assistantUser, normalCandidate, "withdraw", 403, null);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void simultaneousTeamleadRoleKeepsLegacyFinalOutcomeRight() {
        terminal(assistantAndTeamleadUser, normalCandidate, "decline", 200, "DECLINED");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void hrCanStillWithdrawThroughLegacyCandidateRoute() {
        terminal(hrUser, normalCandidate, "withdraw", 200, "WITHDRAWN");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void legacyCandidateTerminalCannotCrossAHiddenPartnerApplication() {
        makeNormalCandidateMixedPartnerHire(hrUser, false);

        terminal(hrUser, normalCandidate, "decline", 403, null);

        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertCircleMember(em, partnerPosition, hrUser));
        terminal(hrUser, normalCandidate, "withdraw", 200, "WITHDRAWN");
    }

    // ---- Application list exposes the candidate-scoped dossier capability -------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantNamedOwner_runsHireButApplicationEnvelopeDeniesDossier() {
        setHiringOwner(teamPosition, assistantUser);

        given().header("X-Requested-By", assistantUser)
                .when().get("/recruitment/candidates/{uuid}/applications", normalCandidate)
                .then().statusCode(200)
                .body("viewerCanReadDossier", org.hamcrest.Matchers.equalTo(false))
                .body("applications[0].viewerRunsHire", org.hamcrest.Matchers.equalTo(true))
                .body("applications[0].viewerCanReadDossier", org.hamcrest.Matchers.equalTo(false));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void namedTeamleadGetsReadOnlyDossierCapability() {
        given().header("X-Requested-By", owningTeamlead)
                .when().get("/recruitment/candidates/{uuid}/applications", normalCandidate)
                .then().statusCode(200)
                .body("viewerCanReadDossier", org.hamcrest.Matchers.equalTo(true))
                .body("applications[0].viewerRunsHire", org.hamcrest.Matchers.equalTo(true));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hrCapabilityIsPresentForLegacyCandidateWithNoApplications() {
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/applications", legacyDossierCandidate)
                .then().statusCode(200)
                .body("applications", org.hamcrest.Matchers.hasSize(0))
                .body("viewerCanReadDossier", org.hamcrest.Matchers.equalTo(true));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hiddenPartnerOwnershipCannotConferRunsHireOrDossierOnMixedCandidate() {
        makeNormalCandidateMixedPartnerHire(teamleadRoleUser, false);

        given().header("X-Requested-By", teamleadRoleUser)
                .when().get("/recruitment/candidates/{uuid}/applications", normalCandidate)
                .then().statusCode(200)
                .body("viewerCanReadDossier", org.hamcrest.Matchers.equalTo(false))
                .body("applications", org.hamcrest.Matchers.hasSize(1))
                .body("applications[0].positionTrack",
                        org.hamcrest.Matchers.equalTo("PRACTICE_TEAM"))
                .body("applications[0].viewerRunsHire",
                        org.hamcrest.Matchers.equalTo(false));
        listRevisions(teamleadRoleUser, normalCandidate, 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerCircleMembershipAllowsNamedTeamleadToRunMixedHire() {
        makeNormalCandidateMixedPartnerHire(teamleadRoleUser, true);

        given().header("X-Requested-By", teamleadRoleUser)
                .when().get("/recruitment/candidates/{uuid}/applications", normalCandidate)
                .then().statusCode(200)
                .body("viewerCanReadDossier", org.hamcrest.Matchers.equalTo(true))
                .body("applications", org.hamcrest.Matchers.hasSize(2))
                .body("applications.positionTrack", org.hamcrest.Matchers.hasItems(
                        "PRACTICE_TEAM", "PARTNER"))
                .body("applications.viewerRunsHire",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo(true)));
        listRevisions(teamleadRoleUser, normalCandidate, 200);
    }

    // ---- Candidate profile/list redact dossier metadata for narrower viewers ------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantProfileResponseRedactsAllDossierMetadata() {
        given().header("X-Requested-By", assistantUser)
                .when().get("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(200)
                .body("targetCompanyUuid", org.hamcrest.Matchers.nullValue())
                .body("targetStartDate", org.hamcrest.Matchers.nullValue())
                .body("notes", org.hamcrest.Matchers.nullValue())
                .body("latestRevision", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void namedTeamleadProfileResponseRetainsReadOnlyDossierMetadata() {
        given().header("X-Requested-By", owningTeamlead)
                .when().get("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(200)
                .body("targetCompanyUuid", org.hamcrest.Matchers.equalTo(targetCompanyUuid))
                .body("targetStartDate", org.hamcrest.Matchers.equalTo("2026-09-01"))
                .body("notes", org.hamcrest.Matchers.equalTo("offer-only note"))
                .body("latestRevision.kind", org.hamcrest.Matchers.equalTo("REVIEW_PDF"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantCandidateGridRedactsDossierMetadata() {
        given().header("X-Requested-By", assistantUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(1))
                .body("data[0].companyUuid", org.hamcrest.Matchers.nullValue())
                .body("data[0].templateUuid", org.hamcrest.Matchers.nullValue())
                .body("data[0].latestRevisionKind", org.hamcrest.Matchers.nullValue())
                .body("data[0].latestRevisionAt", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCandidateGridAndBulkTagCloseWhenCandidateIsHired() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_candidates
                                   SET status = 'HIRED'
                                 WHERE uuid = :candidate
                                """)
                        .setParameter("candidate", normalCandidate)
                        .executeUpdate());

        given().header("X-Requested-By", assistantUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(0));
        getCandidate(assistantUser, normalCandidate, 404);

        given().header("X-Requested-By", assistantUser)
                .contentType("application/json")
                .body(Map.of(
                        "candidateUuids", List.of(normalCandidate),
                        "addTags", List.of("must-not-stick")))
                .when().post("/recruitment/candidates/tags/bulk")
                .then().statusCode(404);

        given().header("X-Requested-By", teamleadRoleUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(0));
        given().header("X-Requested-By", teamleadRoleUser)
                .contentType("application/json")
                .body(Map.of(
                        "candidateUuids", List.of(normalCandidate),
                        "addTags", List.of("must-not-stick")))
                .when().post("/recruitment/candidates/tags/bulk")
                .then().statusCode(404);

        given().header("X-Requested-By", hrUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(1));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hrCandidateGridRetainsDossierMetadata() {
        given().header("X-Requested-By", hrUser)
                .queryParam("search", "PII_SENTINEL Anna")
                .when().get("/recruitment/candidates")
                .then().statusCode(200)
                .body("data", org.hamcrest.Matchers.hasSize(1))
                .body("data[0].companyUuid", org.hamcrest.Matchers.equalTo(targetCompanyUuid))
                .body("data[0].templateUuid", org.hamcrest.Matchers.equalTo(dossierTemplateUuid))
                .body("data[0].latestRevisionKind", org.hamcrest.Matchers.equalTo("REVIEW_PDF"));
    }

    // ---- Dossier-era candidate fields require dossier WRITE ----------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCannotUpdateDossierEraCandidateFields() {
        given().header("X-Requested-By", assistantUser)
                .contentType("application/json")
                .body(Map.of(
                        "targetCompanyUuid", UUID.randomUUID().toString(),
                        "targetStartDate", "2026-10-01",
                        "notes", "assistant must not write this"))
                .when().put("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(403);

        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(200)
                .body("targetCompanyUuid", org.hamcrest.Matchers.equalTo(targetCompanyUuid))
                .body("targetStartDate", org.hamcrest.Matchers.equalTo("2026-09-01"))
                .body("notes", org.hamcrest.Matchers.equalTo("offer-only note"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void hrCanUpdateDossierEraCandidateFields() {
        String company = UUID.randomUUID().toString();
        given().header("X-Requested-By", hrUser)
                .contentType("application/json")
                .body(Map.of(
                        "targetCompanyUuid", company,
                        "targetStartDate", "2026-10-01",
                        "notes", "HR update"))
                .when().put("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(200)
                .body("targetCompanyUuid", org.hamcrest.Matchers.equalTo(company))
                .body("targetStartDate", org.hamcrest.Matchers.equalTo("2026-10-01"))
                .body("notes", org.hamcrest.Matchers.equalTo("HR update"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void adminCanUpdateDossierEraCandidateFields() {
        given().header("X-Requested-By", adminUser)
                .contentType("application/json")
                .body(Map.of("notes", "Admin update"))
                .when().put("/recruitment/candidates/{uuid}", normalCandidate)
                .then().statusCode(200)
                .body("notes", org.hamcrest.Matchers.equalTo("Admin update"));
    }

    // ---- Helpers ---------------------------------------------------------------

    private static void getCandidate(String viewer, String candidateUuid, int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .when().get("/recruitment/candidates/{uuid}", candidateUuid)
                .then().statusCode(expectedStatus);
    }

    private static void listRevisions(String viewer, String candidateUuid, int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .when().get("/recruitment/candidates/{uuid}/dossier/revisions", candidateUuid)
                .then().statusCode(expectedStatus);
    }

    private static void candidateTokenGet(String viewer, String candidateUuid,
                                          int expectedStatus, String expectedTokenUuid) {
        var response = given().header("X-Requested-By", viewer)
                .when().get("/onboarding/tokens/candidate/{candidateUuid}", candidateUuid)
                .then().statusCode(expectedStatus);
        if (expectedTokenUuid != null) {
            response.body("uuid", org.hamcrest.Matchers.equalTo(expectedTokenUuid));
        }
    }

    private static void employeeTokenGet(String viewer, String userUuid,
                                         int expectedStatus, String expectedTokenUuid) {
        var response = given().header("X-Requested-By", viewer)
                .when().get("/onboarding/tokens/user/{userUuid}", userUuid)
                .then().statusCode(expectedStatus);
        if (expectedTokenUuid != null) {
            response.body("uuid", org.hamcrest.Matchers.equalTo(expectedTokenUuid));
        }
    }

    private static String createCandidateToken(String viewer, String candidateUuid,
                                               int expectedStatus) {
        return createOnboardingToken(viewer, candidateTokenBody(candidateUuid), expectedStatus);
    }

    private static String createEmployeeToken(String viewer, String userUuid,
                                              int expectedStatus) {
        return createOnboardingToken(viewer, employeeTokenBody(userUuid), expectedStatus);
    }

    private static String createOnboardingToken(String viewer, Map<String, Object> body,
                                                int expectedStatus) {
        var response = given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .body(body)
                .when().post("/onboarding/tokens")
                .then().statusCode(expectedStatus);
        return expectedStatus == 200 ? response.extract().path("uuid") : null;
    }

    private static void mutateToken(String viewer, String tokenUuid, String method,
                                    Map<String, Object> body, int expectedStatus) {
        var request = given().header("X-Requested-By", viewer);
        if (body != null) {
            request.contentType("application/json").body(body);
        }
        io.restassured.response.Response response = switch (method) {
            case "PUT" -> request.when().put("/onboarding/tokens/{uuid}", tokenUuid);
            case "DELETE" -> request.when().delete("/onboarding/tokens/{uuid}", tokenUuid);
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        response.then().statusCode(expectedStatus);
    }

    private static Map<String, Object> candidateTokenBody(String candidateUuid) {
        return Map.of(
                "candidateUuid", candidateUuid,
                "showDriversLicense", true,
                "showHealthInsurance", true,
                "showCriminalRecord", false,
                "expiresInDays", 30);
    }

    private static Map<String, Object> employeeTokenBody(String userUuid) {
        return Map.of(
                "userUuid", userUuid,
                "showDriversLicense", true,
                "showHealthInsurance", false,
                "showCriminalRecord", true,
                "expiresInDays", 30);
    }

    private static void terminal(String viewer, String candidateUuid, String outcome,
                                 int expectedStatus, String expectedCandidateStatus) {
        var response = given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .body("{\"reason\":\"authorization regression\"}")
                .when().post("/recruitment/candidates/{uuid}/{outcome}", candidateUuid, outcome)
                .then().statusCode(expectedStatus);
        if (expectedCandidateStatus != null) {
            response.body("status", org.hamcrest.Matchers.equalTo(expectedCandidateStatus));
        }
    }

    private static void moveToInterviewOne(String viewer, String applicationUuid,
                                           int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .body("{\"stage\":\"INTERVIEW_1\"}")
                .when().post("/recruitment/applications/{uuid}/stage", applicationUuid)
                .then().statusCode(expectedStatus);
    }

    private void editDiscussionNote(String viewer, String eventId, String text,
                                    int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .body(Map.of("text", text))
                .when().put("/recruitment/candidates/{uuid}/notes/{eventId}",
                        normalCandidate, eventId)
                .then().statusCode(expectedStatus);
    }

    private static void replaceInterviewResourcePins(String viewer, String positionUuid,
                                                     int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .body("{\"resourceUuids\":[]}")
                .when().put("/recruitment/interview-resources/pins/{positionUuid}",
                        positionUuid)
                .then().statusCode(expectedStatus);
    }

    private void promotionRepair(String viewer, String action, int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .when().post("/recruitment/candidates/{uuid}/promotion/{action}",
                        normalCandidate, action)
                .then().statusCode(expectedStatus);
    }

    private void markCandidateConverted() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_candidates
                                   SET converted_user_uuid = :user
                                 WHERE uuid = :candidate
                                """)
                        .setParameter("user", hrUser)
                        .setParameter("candidate", normalCandidate)
                        .executeUpdate());
    }

    private List<String> candidateUuids() {
        return List.of(normalCandidate, outOfPracticeCandidate,
                partnerOnlyCandidate, legacyDossierCandidate);
    }

    private void setUserPractice(String userUuid, String userPracticeUuid) {
        em.createNativeQuery("UPDATE user SET practice_uuid = :practice WHERE uuid = :user")
                .setParameter("practice", userPracticeUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void setHiringOwner(String positionUuid, String ownerUuid) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_positions
                                   SET hiring_owner_uuid = :owner
                                 WHERE uuid = :position
                                """)
                        .setParameter("owner", ownerUuid)
                        .setParameter("position", positionUuid)
                        .executeUpdate());
    }

    private void insertOnboardingToken(String tokenUuid, String candidateUuid, String userUuid) {
        String ownerColumn = candidateUuid != null ? "candidate_uuid" : "user_uuid";
        String ownerUuid = candidateUuid != null ? candidateUuid : userUuid;
        em.createNativeQuery("""
                        INSERT INTO onboarding_upload_tokens
                               (uuid, %s, expires_at, created_by_useruuid)
                        VALUES (:uuid, :owner,
                                DATE_ADD(NOW(), INTERVAL 7 DAY), :creator)
                        """.formatted(ownerColumn))
                .setParameter("uuid", tokenUuid)
                .setParameter("owner", ownerUuid)
                .setParameter("creator", hrUser)
                .executeUpdate();
    }

    private void makeNormalCandidateMixedPartnerHire(String ownerUuid, boolean addToCircle) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            UPDATE recruitment_positions
                               SET hiring_owner_uuid = :owner
                             WHERE uuid = :position
                            """)
                    .setParameter("owner", ownerUuid)
                    .setParameter("position", partnerPosition)
                    .executeUpdate();
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    normalCandidate, partnerPosition, "SCREENING");
            if (addToCircle) {
                P8ProfileFixtures.insertCircleMember(em, partnerPosition, ownerUuid);
            }
        });
    }

    private void setDossierEraCandidateMetadata(String candidateUuid) {
        em.createNativeQuery("""
                        UPDATE recruitment_candidates
                           SET target_company_uuid = :company,
                               target_start_date = '2026-09-01',
                               notes = 'offer-only note'
                         WHERE uuid = :candidate
                        """)
                .setParameter("company", targetCompanyUuid)
                .setParameter("candidate", candidateUuid)
                .executeUpdate();
    }

    private void insertDossier(String candidateUuid) {
        String dossierUuid = UUID.randomUUID().toString();
        em.createNativeQuery("""
                        INSERT INTO candidate_dossiers
                            (uuid, candidate_uuid, template_uuid, status, created_at, updated_at)
                        VALUES (:uuid, :candidate, :template, 'OPEN', :now, :now)
                        """)
                .setParameter("uuid", dossierUuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("template", dossierTemplateUuid)
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO candidate_dossier_revisions
                            (uuid, dossier_uuid, version_number, kind,
                             placeholder_values_snapshot, signers_config_snapshot,
                             appendices_snapshot, recipient_email, sent_by_useruuid, created_at)
                        VALUES (:uuid, :dossier, 1, 'REVIEW_PDF', '{}', '[]', '[]',
                                'candidate@example.invalid', :actor, :now)
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("dossier", dossierUuid)
                .setParameter("actor", hrUser)
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
    }
}
