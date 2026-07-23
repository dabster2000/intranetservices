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
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.DOSSIER_FLAG;
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
    private String teamA;

    private String hrUser;
    private String techPartnerUser;
    private String circleHr;
    private String adminUser;
    private String plainUser;

    private String teamPosition;
    private String partnerPosition;

    private String normalCandidate;
    private String partnerOnlyCandidate;
    private String legacyDossierCandidate;

    private String previousFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamA = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        techPartnerUser = UUID.randomUUID().toString();
        circleHr = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        teamPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        normalCandidate = UUID.randomUUID().toString();
        partnerOnlyCandidate = UUID.randomUUID().toString();
        legacyDossierCandidate = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, techPartnerUser, "Tino", "Techpartner");
            P8ProfileFixtures.insertUser(em, circleHr, "Cirkel", "Recruiter");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertRole(em, techPartnerUser, "TECHPARTNER");
            P8ProfileFixtures.insertRole(em, circleHr, "HR");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");
            P8ProfileFixtures.insertPractice(em, practiceUuid);

            P8ProfileFixtures.insertPosition(em, teamPosition, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, teamA, null);
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertCircleMember(em, partnerPosition, circleHr);

            P8ProfileFixtures.insertCandidate(em, normalCandidate,
                    "PII_SENTINEL Anna", "PII_SENTINEL Ager", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, partnerOnlyCandidate,
                    "PII_SENTINEL Gro", "PII_SENTINEL Gram", "ACTIVE", null, null, hrUser);
            // Legacy dossier-only candidate: no application at all (pre-ATS flow).
            P8ProfileFixtures.insertCandidate(em, legacyDossierCandidate,
                    "PII_SENTINEL Lars", "PII_SENTINEL Legacy", "ACTIVE", null, null, hrUser);

            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    normalCandidate, teamPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    partnerOnlyCandidate, partnerPosition, "SCREENING");

            // A dossier for the normal candidate so the /dossier/revisions
            // subpath returns 200 for a visible viewer (distinguishing a
            // gate 404 from a dossier-missing 404). template_uuid is a soft FK.
            insertDossier(normalCandidate);

            previousFlag = P8ProfileFixtures.setFlag(em, DOSSIER_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            // candidate_dossiers.candidate_uuid is ON DELETE RESTRICT — clear
            // dossiers before the shared cleanup deletes the candidates.
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid IN :c")
                    .setParameter("c", List.of(normalCandidate, partnerOnlyCandidate, legacyDossierCandidate))
                    .executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(normalCandidate, partnerOnlyCandidate, legacyDossierCandidate),
                    List.of(teamPosition, partnerPosition),
                    List.of(hrUser, techPartnerUser, circleHr, adminUser, plainUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, DOSSIER_FLAG, previousFlag);
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
    void techPartner_readsPlainCandidate_ok() {
        // TECHPARTNER is in the profile-read tier — the dossier flow's
        // existing production audience must keep working.
        getCandidate(techPartnerUser, normalCandidate, 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void legacyDossierOnlyCandidate_zeroApplications_visibleToProfileTier() {
        // A pre-ATS dossier candidate has no applications and so is never
        // "partner-track-only" — it must stay visible to HR and TECHPARTNER.
        getCandidate(hrUser, legacyDossierCandidate, 200);
        getCandidate(techPartnerUser, legacyDossierCandidate, 200);
    }

    // ---- Partner-track hard filter --------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hr_onPartnerTrackOnlyCandidateOutsideCircle_404() {
        getCandidate(hrUser, partnerOnlyCandidate, 404);
        getCandidate(techPartnerUser, partnerOnlyCandidate, 404);
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

    private void insertDossier(String candidateUuid) {
        em.createNativeQuery("""
                        INSERT INTO candidate_dossiers
                            (uuid, candidate_uuid, template_uuid, status, created_at, updated_at)
                        VALUES (:uuid, :candidate, :template, 'OPEN', :now, :now)
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("candidate", candidateUuid)
                .setParameter("template", UUID.randomUUID().toString())
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
    }
}
