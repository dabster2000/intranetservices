package dk.trustworks.intranet.recruitmentservice.resources;

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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression lock for the P4-era detached-entity flush defect (found during
 * P11, 2026-07-23): the resources load entities OUTSIDE any transaction and
 * pass them into {@code @Transactional} service methods — Quarkus Hibernate
 * sessions are transaction-scoped, so mutations on those detached instances
 * returned 200 (and appended their events!) while the state tables silently
 * never changed. Every REST mutation is asserted HERE against the DATABASE
 * in a fresh transaction — never against the response body, which reflects
 * the in-memory instance and lies.
 */
@QuarkusTest
class RecruitmentRestMutationFlushTest {

    @Inject
    EntityManager em;

    private String recruiter;
    private String candidateUuid;
    private String pooledCandidateUuid;
    private String positionUuid;
    private String applicationUuid;
    private String teamUuid;
    private String practiceUuid;
    private String previousFlag;

    @BeforeEach
    void seed() {
        recruiter = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        pooledCandidateUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        practiceUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertUser(em, recruiter, "Rina", "Recruiter");
            P8ProfileFixtures.insertRole(em, recruiter, "HR");
            em.createNativeQuery("""
                            INSERT INTO team (uuid, name, shortname, teamleadbonus, teambonus)
                            VALUES (:uuid, 'Flush Fixture', 'FLX', 0, 0)
                            """)
                    .setParameter("uuid", teamUuid).executeUpdate();
            P8ProfileFixtures.insertPosition(em, positionUuid, "Flush probe", "PRACTICE_TEAM",
                    practiceUuid, teamUuid, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid, "Flush", "Probe",
                    "ACTIVE", null, null, recruiter);
            P8ProfileFixtures.insertCandidate(em, pooledCandidateUuid, "Pool", "Probe",
                    "POOLED", "PROSPECT", null, recruiter);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid, candidateUuid,
                    positionUuid, "SCREENING");
            previousFlag = P8ProfileFixtures.setFlag(em, P8ProfileFixtures.PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid, pooledCandidateUuid),
                    List.of(positionUuid), List.of(recruiter), practiceUuid);
            em.createNativeQuery("DELETE FROM team WHERE uuid = :u")
                    .setParameter("u", teamUuid).executeUpdate();
            P8ProfileFixtures.restoreFlag(em, P8ProfileFixtures.PIPELINE_FLAG, previousFlag);
        });
    }

    // ---- Applications (P4/P10 surface) --------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void stageMove_flushes() {
        post("/recruitment/applications/" + applicationUuid + "/stage",
                Map.of("stage", "INTERVIEW_1"), 200);
        assertEquals("INTERVIEW_1", scalar(
                "SELECT stage FROM recruitment_applications WHERE uuid = :u", applicationUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void reject_flushesTerminalReasonAndRetentionClock() {
        post("/recruitment/applications/" + applicationUuid + "/reject",
                Map.of("reasonCode", "PROFILE_MISMATCH"), 200);
        assertEquals("REJECTED", scalar(
                "SELECT terminal FROM recruitment_applications WHERE uuid = :u", applicationUuid));
        assertEquals("PROFILE_MISMATCH", scalar(
                "SELECT rejection_reason_code FROM recruitment_applications WHERE uuid = :u",
                applicationUuid));
        // The candidate-side retention clock (mutated on the candidate row).
        assertNotNull(scalar(
                "SELECT retention_deadline FROM recruitment_candidates WHERE uuid = :u",
                candidateUuid));
        assertNotNull(scalar(
                "SELECT process_ended_at FROM recruitment_candidates WHERE uuid = :u",
                candidateUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void withdraw_flushes() {
        post("/recruitment/applications/" + applicationUuid + "/withdraw", Map.of(), 200);
        assertEquals("WITHDRAWN", scalar(
                "SELECT terminal FROM recruitment_applications WHERE uuid = :u", applicationUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void returnToPool_flushesTerminalAndPoolStatus() {
        post("/recruitment/applications/" + applicationUuid + "/return-to-pool", Map.of(), 200);
        assertEquals("RETURNED_TO_POOL", scalar(
                "SELECT terminal FROM recruitment_applications WHERE uuid = :u", applicationUuid));
        assertEquals("POOLED", scalar(
                "SELECT status FROM recruitment_candidates WHERE uuid = :u", candidateUuid));
        assertEquals("SILVER_MEDALIST", scalar(
                "SELECT pool_status FROM recruitment_candidates WHERE uuid = :u", candidateUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assignTeam_flushes() {
        post("/recruitment/applications/" + applicationUuid + "/assign-team",
                Map.of("teamUuid", teamUuid), 200);
        assertEquals(teamUuid, scalar(
                "SELECT assigned_team_uuid FROM recruitment_applications WHERE uuid = :u",
                applicationUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void expectedStartDate_flushes() {
        given().header("X-Requested-By", recruiter)
                .contentType(ContentType.JSON)
                .body(Map.of("expectedStartDate", "2026-10-01"))
                .when().put("/recruitment/applications/{uuid}/expected-start-date", applicationUuid)
                .then().statusCode(200);
        assertEquals("2026-10-01", String.valueOf(scalar(
                "SELECT expected_start_date FROM recruitment_applications WHERE uuid = :u",
                applicationUuid)));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void attach_resetsTheRetentionClock_onTheCandidateRow() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_candidates
                                SET process_ended_at = UTC_TIMESTAMP(), retention_deadline = UTC_TIMESTAMP()
                                WHERE uuid = :u
                                """)
                        .setParameter("u", pooledCandidateUuid).executeUpdate());
        given().header("X-Requested-By", recruiter)
                .contentType(ContentType.JSON)
                .body(Map.of("positionUuid", positionUuid))
                .when().post("/recruitment/candidates/{uuid}/applications", pooledCandidateUuid)
                .then().statusCode(201);
        // The create path un-pools and stops the retention clock — on the ROW.
        assertEquals("ACTIVE", scalar(
                "SELECT status FROM recruitment_candidates WHERE uuid = :u", pooledCandidateUuid));
        assertEquals(null, scalar(
                "SELECT process_ended_at FROM recruitment_candidates WHERE uuid = :u",
                pooledCandidateUuid));
        assertEquals(null, scalar(
                "SELECT retention_deadline FROM recruitment_candidates WHERE uuid = :u",
                pooledCandidateUuid));
    }

    // ---- Positions (P2 surface) ------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void positionUpdate_flushes() {
        given().header("X-Requested-By", recruiter)
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Flush probe (renamed)", "hiringTrack", "PRACTICE_TEAM",
                        "practiceUuid", practiceUuid, "teamUuid", teamUuid))
                .when().put("/recruitment/positions/{uuid}", positionUuid)
                .then().statusCode(200);
        assertEquals("Flush probe (renamed)", scalar(
                "SELECT title FROM recruitment_positions WHERE uuid = :u", positionUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void positionClose_flushes() {
        // Close the open application first — or closing is still legal;
        // position close has no open-application guard by design.
        post("/recruitment/positions/" + positionUuid + "/close", Map.of(), 200);
        assertEquals("CLOSED", scalar(
                "SELECT status FROM recruitment_positions WHERE uuid = :u", positionUuid));
        assertNotNull(scalar(
                "SELECT closed_at FROM recruitment_positions WHERE uuid = :u", positionUuid));
    }

    // ---- Helpers ------------------------------------------------------------------------

    private void post(String path, Map<String, ?> body, int expectedStatus) {
        given().header("X-Requested-By", recruiter)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post(path)
                .then().statusCode(expectedStatus);
    }

    /** Read one scalar from the DB in a FRESH transaction — the only honest assert. */
    private Object scalar(String sql, String uuid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery(sql).setParameter("u", uuid).getResultList();
            return rows.isEmpty() ? null : rows.get(0);
        });
    }
}
