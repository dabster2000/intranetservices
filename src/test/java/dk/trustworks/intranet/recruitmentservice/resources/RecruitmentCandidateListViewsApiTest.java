package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.DOSSIER_FLAG;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8 DoD (database grid): the {@code view} parameter of
 * {@code GET /recruitment/candidates} — per-view result sets
 * (ACTIVE_PIPELINE / TALENT_POOL / SILVER_MEDALISTS / ALL), composability
 * with the existing filters, invalid values answering 400 — plus the
 * partner-row gap (P4 carry-over): candidates whose applications are ALL
 * partner-track are excluded for viewers outside those circles, ADMIN and
 * circle members see them, zero-application candidates stay visible. The
 * shared local DB carries unrelated rows, so every assertion is
 * contains/not-contains on fixture uuids, never an exact set.
 */
@QuarkusTest
class RecruitmentCandidateListViewsApiTest {

    private static final String CANDIDATES = "/recruitment/candidates";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String hrUser;
    private String adminUser;
    private String circleHr;

    private String normalPosition;
    private String partnerPosition;

    private String activeCandidate;
    private String pooledCandidate;
    private String silverCandidate;
    private String partnerOnlyCandidate;
    private String noAppCandidate;
    private String hiredCandidate;

    private String tagMarker;
    private String previousFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        circleHr = UUID.randomUUID().toString();
        normalPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        activeCandidate = UUID.randomUUID().toString();
        pooledCandidate = UUID.randomUUID().toString();
        silverCandidate = UUID.randomUUID().toString();
        partnerOnlyCandidate = UUID.randomUUID().toString();
        noAppCandidate = UUID.randomUUID().toString();
        hiredCandidate = UUID.randomUUID().toString();
        tagMarker = "p8view-" + UUID.randomUUID().toString().substring(0, 8);

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertUser(em, circleHr, "Cirkel", "Recruiter");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");
            P8ProfileFixtures.insertRole(em, circleHr, "HR");
            P8ProfileFixtures.insertPractice(em, practiceUuid);

            P8ProfileFixtures.insertPosition(em, normalPosition, "Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertCircleMember(em, partnerPosition, circleHr);

            P8ProfileFixtures.insertCandidate(em, activeCandidate,
                    "PII_SENTINEL Anna", "PII_SENTINEL Aktiv", "ACTIVE", null,
                    "[\"" + tagMarker + "\"]", hrUser);
            P8ProfileFixtures.insertCandidate(em, pooledCandidate,
                    "PII_SENTINEL Pia", "PII_SENTINEL Pool", "POOLED", "PROSPECT", null, hrUser);
            P8ProfileFixtures.insertCandidate(em, silverCandidate,
                    "PII_SENTINEL Sif", "PII_SENTINEL Sølv", "POOLED", "SILVER_MEDALIST",
                    null, hrUser);
            P8ProfileFixtures.insertCandidate(em, partnerOnlyCandidate,
                    "PII_SENTINEL Gro", "PII_SENTINEL Gram", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, noAppCandidate,
                    "PII_SENTINEL Nul", "PII_SENTINEL Nyholm", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, hiredCandidate,
                    "PII_SENTINEL Hans", "PII_SENTINEL Hyre", "HIRED", null, null, hrUser);

            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    activeCandidate, normalPosition, "SCREENING");
            // The converted hire: markHired sets stage HIRED and leaves
            // terminal NULL — the exact row shape that used to keep hired
            // people sitting in the Active-pipeline view forever.
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    hiredCandidate, normalPosition, "HIRED");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    partnerOnlyCandidate, partnerPosition, "SCREENING");
            // A closed application alone does not make a pipeline row.
            P8ProfileFixtures.insertClosedApplication(em, UUID.randomUUID().toString(),
                    pooledCandidate, normalPosition, "RETURNED_TO_POOL");

            previousFlag = P8ProfileFixtures.setFlag(em, DOSSIER_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(activeCandidate, pooledCandidate, silverCandidate,
                            partnerOnlyCandidate, noAppCandidate, hiredCandidate),
                    List.of(normalPosition, partnerPosition),
                    List.of(hrUser, adminUser, circleHr),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, DOSSIER_FLAG, previousFlag);
        });
    }

    // ---- Per-view sets ------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void activePipelineView_onlyCandidatesWithOpenApplications() {
        List<String> uuids = listUuids(hrUser, "view", "ACTIVE_PIPELINE");
        assertTrue(uuids.contains(activeCandidate), "open application → in the pipeline view");
        assertFalse(uuids.contains(pooledCandidate), "closed application only → not in pipeline");
        assertFalse(uuids.contains(noAppCandidate), "no applications → not in pipeline");
        assertFalse(uuids.contains(partnerOnlyCandidate),
                "partner-track-only stays excluded inside views (partner-row gap)");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void activePipelineView_excludesTheHiredStage() {
        // HIRED is a stage, not a terminal — markHired leaves terminal NULL,
        // so "terminal IS NULL" alone parked every converted hire in this
        // view permanently. "Still in play" excludes the HIRED stage.
        List<String> pipeline = listUuids(hrUser, "view", "ACTIVE_PIPELINE");
        assertFalse(pipeline.contains(hiredCandidate),
                "a hired candidate is no longer in an active pipeline");
        assertTrue(pipeline.contains(activeCandidate),
                "the exclusion must not take genuinely open applications with it");

        // The row itself is untouched — it is only out of THIS view.
        assertTrue(listUuids(hrUser, "view", "ALL").contains(hiredCandidate),
                "the hired candidate still exists in the database");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void talentPoolView_isThePooledStatus() {
        List<String> uuids = listUuids(hrUser, "view", "TALENT_POOL");
        assertTrue(uuids.contains(pooledCandidate));
        assertTrue(uuids.contains(silverCandidate), "silver medalists are pooled too");
        assertFalse(uuids.contains(activeCandidate));
        assertFalse(uuids.contains(noAppCandidate));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void silverMedalistsView_isThePoolBucket() {
        List<String> uuids = listUuids(hrUser, "view", "SILVER_MEDALISTS");
        assertTrue(uuids.contains(silverCandidate));
        assertFalse(uuids.contains(pooledCandidate), "PROSPECT bucket is not a silver medalist");
        assertFalse(uuids.contains(activeCandidate));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void allViewExplicit_matchesTheDefault() {
        List<String> explicit = listUuids(hrUser, "view", "ALL");
        assertTrue(explicit.contains(activeCandidate));
        assertTrue(explicit.contains(pooledCandidate));
        assertTrue(explicit.contains(silverCandidate));
        assertTrue(explicit.contains(noAppCandidate));
    }

    // ---- Composability + validation -------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void view_composesWithTheExistingFilters() {
        // view + tag: the pipeline view narrowed to the marker tag.
        List<String> pipelineTagged = given().header("X-Requested-By", hrUser)
                .queryParam("view", "ACTIVE_PIPELINE").queryParam("tag", tagMarker)
                .queryParam("size", 500)
                .when().get(CANDIDATES)
                .then().statusCode(200)
                .extract().jsonPath().getList("data.uuid", String.class);
        assertTrue(pipelineTagged.contains(activeCandidate));
        // The same tag under TALENT_POOL matches nothing of ours — the two
        // predicates AND together.
        List<String> poolTagged = given().header("X-Requested-By", hrUser)
                .queryParam("view", "TALENT_POOL").queryParam("tag", tagMarker)
                .queryParam("size", 500)
                .when().get(CANDIDATES)
                .then().statusCode(200)
                .extract().jsonPath().getList("data.uuid", String.class);
        assertFalse(poolTagged.contains(activeCandidate));
        assertFalse(poolTagged.contains(pooledCandidate));
    }

    // ---- Free-text search spans tags ------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void search_matchesTagsAsWellAsNameAndEmail() {
        // Whole tag, and a substring of it — a search box that only did
        // exact tags would make tags unusable for discovery.
        assertTrue(listUuids(hrUser, "search", tagMarker).contains(activeCandidate),
                "the full tag finds the candidate");
        assertTrue(listUuids(hrUser, "search", tagMarker.substring(0, 8)).contains(activeCandidate),
                "a tag prefix finds the candidate");
        assertTrue(listUuids(hrUser, "search", tagMarker.toUpperCase()).contains(activeCandidate),
                "tag search is case-insensitive");

        // Name search still works, and the tag arm did not widen it.
        List<String> byName = listUuids(hrUser, "search", "PII_SENTINEL Pool");
        assertTrue(byName.contains(pooledCandidate));
        assertFalse(byName.contains(activeCandidate));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void search_onJsonPunctuationMatchesNothing() {
        // JSON_SEARCH looks inside the array ELEMENTS, so the column's own
        // punctuation never turns a stray comma into "every tagged row".
        assertFalse(listUuids(hrUser, "search", ",").contains(activeCandidate));
        assertFalse(listUuids(hrUser, "search", "\"").contains(activeCandidate));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void invalidView_answers400() {
        given().header("X-Requested-By", hrUser)
                .queryParam("view", "NOT_A_VIEW")
                .when().get(CANDIDATES)
                .then().statusCode(400);
    }

    // ---- Partner-row gap (P4 carry-over) --------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerRowGap_excludedForOutsiders_visibleToAdminAndCircle_noAppRowsStay() {
        List<String> hrRows = listUuids(hrUser, null, null);
        assertFalse(hrRows.contains(partnerOnlyCandidate),
                "partner-track-only rows are invisible outside the circle");
        assertTrue(hrRows.contains(noAppCandidate),
                "zero-application candidates always stay visible");
        assertTrue(hrRows.contains(activeCandidate));

        assertTrue(listUuids(adminUser, null, null).contains(partnerOnlyCandidate),
                "ADMIN sees every row");
        assertTrue(listUuids(circleHr, null, null).contains(partnerOnlyCandidate),
                "circle members see their partner rows");
    }

    // ---- Row shape ------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void rows_exposeUpdatedAt_forTheGridsUpdatedColumn() {
        given().header("X-Requested-By", hrUser)
                .queryParam("view", "ACTIVE_PIPELINE").queryParam("tag", tagMarker)
                .queryParam("size", 500)
                .when().get(CANDIDATES)
                .then().statusCode(200)
                .body("data.find { it.uuid == '" + activeCandidate + "' }.updatedAt",
                        Matchers.notNullValue());
    }

    // ---- Helpers --------------------------------------------------------------------

    private List<String> listUuids(String viewer, String paramName, String paramValue) {
        var request = given().header("X-Requested-By", viewer).queryParam("size", 1000);
        if (paramName != null) {
            request = request.queryParam(paramName, paramValue);
        }
        Response response = request.when().get(CANDIDATES);
        response.then().statusCode(200);
        return response.jsonPath().getList("data.uuid", String.class);
    }
}
