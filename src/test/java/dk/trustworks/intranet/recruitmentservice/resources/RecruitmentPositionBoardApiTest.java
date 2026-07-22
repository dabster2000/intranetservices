package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * P7 DoD (board endpoint, end-to-end through the resource):
 * <ul>
 *   <li>grouping: one column per stage-set entry in set order — including
 *       HIRED as a normal column, never in the terminal rail;</li>
 *   <li>in-column ordering: oldest {@code stage_entered_at} first;</li>
 *   <li>idle computation server-side from a backdated
 *       {@code stage_entered_at} fixture ({@code daysInStage > 7});</li>
 *   <li>terminal rail: REJECTED/WITHDRAWN/RETURNED_TO_POOL counts +
 *       entries newest-first with {@code rejectionReasonCode};</li>
 *   <li>referral cards resolve {@code referred_by_user_uuid} to a
 *       display name;</li>
 *   <li>visibility matrix via {@code RecruitmentVisibility.canReadPosition}
 *       (roles resolved from real {@code roles}/{@code teamroles}/
 *       {@code practice_lead} fixture rows via X-Requested-By): circle
 *       member sees the partner board, non-circle recruiter answers 404,
 *       practice lead reads own-practice non-partner boards but not
 *       partner content, teamlead of the position's team sees it,
 *       uninvolved employee answers 404 — invisible is ALWAYS 404,
 *       never 403;</li>
 *   <li>pipeline flag off → 404 for non-admin callers.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentPositionBoardApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";
    private static final String BOARD = "/recruitment/positions/{uuid}/board";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String teamUuid;

    private String recruiterUser;    // HR role, NOT in the partner circle
    private String circleRecruiter;  // HR role + circle member on the partner position
    private String practiceLeadUser; // current practice_lead row, no roles
    private String teamleadUser;     // teamroles LEADER of teamUuid
    private String plainUser;        // no roles, no leads, no circles
    private String referrerUser;     // "Refer Rachel" — referred_by target
    private String staffOwnerUser;   // hiring_owner of the staff position, no roles

    private String practicePositionUuid;
    private String partnerPositionUuid;
    private String staffPositionUuid;

    private String referralCandidate;   // Anna Ager, REFERRAL by referrerUser
    private String linkedinCandidate;   // Bo Berg, LINKEDIN_SEARCH
    private String hiredCandidate;      // Carla Chu, application in HIRED stage
    private String rejectedCandidate;   // Dan Dahl
    private String withdrawnCandidate;  // Eva Eng
    private String pooledCandidate;     // Finn Falk
    private String partnerCandidate;    // Gro Gram, on the partner position
    private String staffCandidate;      // Ida Iversen, on the staff position

    private String idleApplication;      // SCREENING, 9 days ago
    private String freshApplication;     // SCREENING, 2 days ago
    private String hiredApplication;     // HIRED stage, open
    private String rejectedApplication;  // terminal 3 days ago
    private String withdrawnApplication; // terminal 1 day ago
    private String pooledApplication;    // terminal 2 days ago
    private String partnerApplication;
    private String staffApplication;

    private String previousFlagValue;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        circleRecruiter = UUID.randomUUID().toString();
        practiceLeadUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        referrerUser = UUID.randomUUID().toString();
        staffOwnerUser = UUID.randomUUID().toString();
        practicePositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        staffPositionUuid = UUID.randomUUID().toString();
        referralCandidate = UUID.randomUUID().toString();
        linkedinCandidate = UUID.randomUUID().toString();
        hiredCandidate = UUID.randomUUID().toString();
        rejectedCandidate = UUID.randomUUID().toString();
        withdrawnCandidate = UUID.randomUUID().toString();
        pooledCandidate = UUID.randomUUID().toString();
        partnerCandidate = UUID.randomUUID().toString();
        staffCandidate = UUID.randomUUID().toString();
        idleApplication = UUID.randomUUID().toString();
        freshApplication = UUID.randomUUID().toString();
        hiredApplication = UUID.randomUUID().toString();
        rejectedApplication = UUID.randomUUID().toString();
        withdrawnApplication = UUID.randomUUID().toString();
        pooledApplication = UUID.randomUUID().toString();
        partnerApplication = UUID.randomUUID().toString();
        staffApplication = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            insertUser(recruiterUser, "Rina", "Recruiter");
            insertUser(circleRecruiter, "Cirkel", "Recruiter");
            insertUser(practiceLeadUser, "Pia", "Lead");
            insertUser(teamleadUser, "Tim", "Teamlead");
            insertUser(plainUser, "Palle", "Plain");
            insertUser(referrerUser, "Refer", "Rachel");
            insertUser(staffOwnerUser, "Olga", "Owner");
            insertRole(recruiterUser, "HR");
            insertRole(circleRecruiter, "HR");
            insertRole(teamleadUser, "TEAMLEAD");
            insertPractice(practiceUuid);
            insertPracticeLead(practiceLeadUser, practiceUuid);
            insertTeamLeader(teamleadUser, teamUuid);

            insertPosition(practicePositionUuid, "Consultant", "PRACTICE_TEAM", practiceUuid, teamUuid,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"OFFER\",\"HIRED\"]");
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER", null, null,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"INTERVIEW_2\",\"INTERVIEW_3\",\"OFFER\",\"HIRED\"]");
            insertCircleMember(partnerPositionUuid, circleRecruiter);
            insertStaffPosition(staffPositionUuid, "Office manager", staffOwnerUser,
                    "[\"SCREENING\",\"INTERVIEW_1\",\"OFFER\",\"HIRED\"]");

            insertCandidate(referralCandidate, "Anna", "Ager", "REFERRAL", referrerUser);
            insertCandidate(linkedinCandidate, "Bo", "Berg", "LINKEDIN_SEARCH", null);
            insertCandidate(hiredCandidate, "Carla", "Chu", null, null);
            insertCandidate(rejectedCandidate, "Dan", "Dahl", null, null);
            insertCandidate(withdrawnCandidate, "Eva", "Eng", null, null);
            insertCandidate(pooledCandidate, "Finn", "Falk", null, null);
            insertCandidate(partnerCandidate, "Gro", "Gram", null, null);
            insertCandidate(staffCandidate, "Ida", "Iversen", null, null);

            insertOpenApplication(idleApplication, referralCandidate, practicePositionUuid,
                    "SCREENING", 9, null, null);
            insertOpenApplication(freshApplication, linkedinCandidate, practicePositionUuid,
                    "SCREENING", 2, "2026-09-01", teamUuid);
            insertOpenApplication(hiredApplication, hiredCandidate, practicePositionUuid,
                    "HIRED", 0, null, null);
            insertClosedApplication(rejectedApplication, rejectedCandidate, practicePositionUuid,
                    "REJECTED", "CULTURE_FIT", 3);
            insertClosedApplication(withdrawnApplication, withdrawnCandidate, practicePositionUuid,
                    "WITHDRAWN", null, 1);
            insertClosedApplication(pooledApplication, pooledCandidate, practicePositionUuid,
                    "RETURNED_TO_POOL", null, 2);
            insertOpenApplication(partnerApplication, partnerCandidate, partnerPositionUuid,
                    "SCREENING", 0, null, null);
            insertOpenApplication(staffApplication, staffCandidate, staffPositionUuid,
                    "INTERVIEW_1", 1, null, null);

            List<?> current = em.createNativeQuery(
                            "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", FLAG).getResultList();
            previousFlagValue = current.isEmpty() ? null : (String) current.get(0);
            if (previousFlagValue == null) {
                em.createNativeQuery("""
                                INSERT INTO app_settings (setting_key, setting_value, category)
                                VALUES (:key, 'true', 'recruitment')
                                """)
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = 'true' WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            }
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<String> positions = List.of(practicePositionUuid, partnerPositionUuid,
                    staffPositionUuid);
            List<String> candidates = List.of(referralCandidate, linkedinCandidate, hiredCandidate,
                    rejectedCandidate, withdrawnCandidate, pooledCandidate, partnerCandidate,
                    staffCandidate);
            List<String> users = List.of(recruiterUser, circleRecruiter, practiceLeadUser,
                    teamleadUser, plainUser, referrerUser, staffOwnerUser);
            em.createNativeQuery("DELETE FROM recruitment_applications WHERE position_uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                    .setParameter("c", candidates).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM practice_lead WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM teamroles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", practiceUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN :u")
                    .setParameter("u", users).executeUpdate();
            if (previousFlagValue == null) {
                em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", previousFlagValue)
                        .setParameter("key", FLAG).executeUpdate();
            }
        });
    }

    // ---- Grouping, ordering, idle, card hydration ------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void board_oneColumnPerStageSetEntry_inSetOrder_withPositionFacts() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("position.uuid", Matchers.equalTo(practicePositionUuid))
                .body("position.name", Matchers.equalTo("Consultant"))
                .body("position.hiringTrack", Matchers.equalTo("PRACTICE_TEAM"))
                .body("position.practiceUuid", Matchers.equalTo(practiceUuid))
                .body("position.practiceName", Matchers.equalTo("Board Fixture"))
                .body("position.teamUuid", Matchers.equalTo(teamUuid))
                .body("position.status", Matchers.equalTo("OPEN"))
                .body("position.demandRag", Matchers.equalTo("GREEN"))
                .body("position.stageSet", Matchers.contains(
                        "SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED"))
                .body("columns.stage", Matchers.contains(
                        "SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED"))
                .body("columns[1].applications", Matchers.hasSize(0))
                .body("columns[3].applications", Matchers.hasSize(0));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void cardsInAColumn_orderedOldestStageEnteredAtFirst() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("columns[0].stage", Matchers.equalTo("SCREENING"))
                .body("columns[0].applications.applicationUuid", Matchers.contains(
                        idleApplication, freshApplication));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void idleFlag_computedServerSide_fromBackdatedStageEnteredAt() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("columns[0].applications[0].daysInStage", Matchers.equalTo(9))
                .body("columns[0].applications[0].idle", Matchers.equalTo(true))
                .body("columns[0].applications[1].daysInStage", Matchers.equalTo(2))
                .body("columns[0].applications[1].idle", Matchers.equalTo(false));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void cards_carryNameSourceReferrerStartDateAndTeam() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("columns[0].applications[0].candidateUuid", Matchers.equalTo(referralCandidate))
                .body("columns[0].applications[0].candidateName", Matchers.equalTo("Anna Ager"))
                .body("columns[0].applications[0].source", Matchers.equalTo("REFERRAL"))
                .body("columns[0].applications[0].referredByName", Matchers.equalTo("Refer Rachel"))
                .body("columns[0].applications[0].stageEnteredAt", Matchers.notNullValue())
                .body("columns[0].applications[1].candidateName", Matchers.equalTo("Bo Berg"))
                .body("columns[0].applications[1].source", Matchers.equalTo("LINKEDIN_SEARCH"))
                .body("columns[0].applications[1].referredByName", Matchers.nullValue())
                .body("columns[0].applications[1].expectedStartDate", Matchers.equalTo("2026-09-01"))
                .body("columns[0].applications[1].assignedTeamUuid", Matchers.equalTo(teamUuid));
    }

    // ---- HIRED is a column, not a terminal --------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hiredApplications_renderAsAColumn_neverInTheTerminalRail() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("columns[4].stage", Matchers.equalTo("HIRED"))
                .body("columns[4].applications.applicationUuid", Matchers.contains(hiredApplication))
                .body("terminal.applications.applicationUuid",
                        Matchers.not(Matchers.hasItem(hiredApplication)));
    }

    // ---- Terminal rail -----------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void terminalRail_summarizesCounts_entriesNewestFirst_withRejectionReason() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("terminal.rejected", Matchers.equalTo(1))
                .body("terminal.withdrawn", Matchers.equalTo(1))
                .body("terminal.returnedToPool", Matchers.equalTo(1))
                // Newest closedAt first: withdrawn (1 day) → pooled (2) → rejected (3).
                .body("terminal.applications.applicationUuid", Matchers.contains(
                        withdrawnApplication, pooledApplication, rejectedApplication))
                .body("terminal.applications[0].outcome", Matchers.equalTo("WITHDRAWN"))
                .body("terminal.applications[0].rejectionReasonCode", Matchers.nullValue())
                .body("terminal.applications[2].outcome", Matchers.equalTo("REJECTED"))
                .body("terminal.applications[2].rejectionReasonCode", Matchers.equalTo("CULTURE_FIT"))
                .body("terminal.applications[2].candidateName", Matchers.equalTo("Dan Dahl"))
                .body("terminal.applications[2].closedAt", Matchers.notNullValue());
    }

    // ---- Visibility matrix (invisible is ALWAYS 404, never 403) -------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerBoard_visibleToCircleMember() {
        given()
                .header("X-Requested-By", circleRecruiter)
                .when().get(BOARD, partnerPositionUuid)
                .then()
                .statusCode(200)
                .body("position.hiringTrack", Matchers.equalTo("PARTNER"))
                .body("columns[0].applications.applicationUuid", Matchers.contains(partnerApplication));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void partnerBoard_answers404ForNonCircleRecruiter() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, partnerPositionUuid)
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void practiceLead_readsOwnPracticeBoard_butNeverPartnerContent() {
        given()
                .header("X-Requested-By", practiceLeadUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("position.uuid", Matchers.equalTo(practicePositionUuid));
        given()
                .header("X-Requested-By", practiceLeadUser)
                .when().get(BOARD, partnerPositionUuid)
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void teamleadOfThePositionsTeam_seesTheBoard() {
        given()
                .header("X-Requested-By", teamleadUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(200)
                .body("position.uuid", Matchers.equalTo(practicePositionUuid));
    }

    // ---- Staff track (P7 DoD: all three track types) ---------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void staffBoard_rendersItsTrimmedStageSet_forRecruiterTier() {
        given()
                .header("X-Requested-By", recruiterUser)
                .when().get(BOARD, staffPositionUuid)
                .then()
                .statusCode(200)
                .body("position.uuid", Matchers.equalTo(staffPositionUuid))
                .body("position.hiringTrack", Matchers.equalTo("STAFF_ROLE"))
                .body("position.practiceUuid", Matchers.nullValue())
                .body("position.hiringOwnerUuid", Matchers.equalTo(staffOwnerUser))
                .body("position.stageSet", Matchers.contains(
                        "SCREENING", "INTERVIEW_1", "OFFER", "HIRED"))
                .body("columns.stage", Matchers.contains(
                        "SCREENING", "INTERVIEW_1", "OFFER", "HIRED"))
                .body("columns[1].applications", Matchers.hasSize(1))
                .body("columns[1].applications[0].candidateName", Matchers.equalTo("Ida Iversen"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void staffBoard_visibleToItsHiringOwner_notToUninvolvedUsers() {
        given()
                .header("X-Requested-By", staffOwnerUser)
                .when().get(BOARD, staffPositionUuid)
                .then()
                .statusCode(200)
                .body("position.uuid", Matchers.equalTo(staffPositionUuid));
        given()
                .header("X-Requested-By", plainUser)
                .when().get(BOARD, staffPositionUuid)
                .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void uninvolvedEmployee_answers404() {
        given()
                .header("X-Requested-By", plainUser)
                .when().get(BOARD, practicePositionUuid)
                .then()
                .statusCode(404);
    }

    // ---- Feature flag --------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void flagOff_answers404ForNonAdminCallers() {
        setFlag("false");
        try {
            given()
                    .header("X-Requested-By", recruiterUser)
                    .when().get(BOARD, practicePositionUuid)
                    .then()
                    .statusCode(404);
        } finally {
            setFlag("true");
        }
    }

    // ---- Fixture helpers -------------------------------------------------------------

    private void setFlag(String value) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", value)
                        .setParameter("key", FLAG).executeUpdate());
    }

    private void insertUser(String uuid, String firstname, String lastname) {
        em.createNativeQuery("""
                        INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                          created, cpr, birthday)
                        VALUES (:uuid, :firstname, :lastname, :email, :username, 'x', 'CONSULTANT',
                                NOW(), '0000000000', '2000-01-01')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("firstname", firstname)
                .setParameter("lastname", lastname)
                .setParameter("email", uuid + "@example.com")
                .setParameter("username", uuid)
                .executeUpdate();
    }

    private void insertRole(String userUuid, String role) {
        em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:uuid, :role, :user)")
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("role", role)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void insertPractice(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, 'Board Fixture', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "B" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertPracticeLead(String userUuid, String practiceUuid) {
        em.createNativeQuery("""
                        INSERT INTO practice_lead (uuid, practice_uuid, useruuid, startdate, enddate,
                                                   created_at, updated_at, created_by)
                        VALUES (:uuid, :practice, :user, '2024-01-01', NULL, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("practice", practiceUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void insertTeamLeader(String userUuid, String teamUuid) {
        em.createNativeQuery("""
                        INSERT INTO teamroles (uuid, teamuuid, useruuid, startdate, enddate, membertype)
                        VALUES (:uuid, :team, :user, '2024-01-01', NULL, 'LEADER')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("team", teamUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String title, String track,
                                String practiceUuid, String teamUuid, String stageSetJson) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, team_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice, :team, :stageSet,
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("team", teamUuid)
                .setParameter("stageSet", stageSetJson)
                .executeUpdate();
    }

    /** STAFF_ROLE position: no practice/team, a named hiring owner (the track's invariant). */
    private void insertStaffPosition(String uuid, String title, String hiringOwnerUuid,
                                     String stageSetJson) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, hiring_owner_uuid, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, 'STAFF_ROLE', :owner, :stageSet,
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("owner", hiringOwnerUuid)
                .setParameter("stageSet", stageSetJson)
                .executeUpdate();
    }

    private void insertCircleMember(String positionUuid, String userUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_circle_members
                            (position_uuid, user_uuid, role_in_circle, added_at, added_by_uuid)
                        VALUES (:p, :u, 'RECRUITER', NOW(3), :u)
                        """)
                .setParameter("p", positionUuid)
                .setParameter("u", userUuid)
                .executeUpdate();
    }

    private void insertCandidate(String uuid, String firstName, String lastName,
                                 String source, String referredBy) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_candidates
                            (uuid, first_name, last_name, status, source, referred_by_user_uuid,
                             created_by_useruuid, created_at, updated_at)
                        VALUES (:uuid, :first, :last, 'ACTIVE', :source, :referredBy,
                                :actor, NOW(), NOW())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("first", firstName)
                .setParameter("last", lastName)
                .setParameter("source", source)
                .setParameter("referredBy", referredBy)
                .setParameter("actor", recruiterUser)
                .executeUpdate();
    }

    /**
     * Open application with a backdated {@code stage_entered_at} —
     * UTC-based ({@code UTC_TIMESTAMP}) to match the entity's
     * {@code LocalDateTime.now(ZoneOffset.UTC)} convention, so the
     * server-computed {@code daysInStage} is exact in assertions.
     */
    private void insertOpenApplication(String uuid, String candidateUuid, String positionUuid,
                                       String stage, int stageDaysAgo,
                                       String expectedStartDate, String assignedTeamUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage, expected_start_date,
                             assigned_team_uuid, stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, :stage, :startDate,
                                :team, UTC_TIMESTAMP(3) - INTERVAL :days DAY, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .setParameter("stage", stage)
                .setParameter("startDate", expectedStartDate)
                .setParameter("team", assignedTeamUuid)
                .setParameter("days", stageDaysAgo)
                .executeUpdate();
    }

    /** Terminal application; {@code updated_at} carries the close timestamp. */
    private void insertClosedApplication(String uuid, String candidateUuid, String positionUuid,
                                         String terminal, String rejectionReason, int closedDaysAgo) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage, terminal, rejection_reason_code,
                             stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, 'SCREENING', :terminal, :reason,
                                UTC_TIMESTAMP(3) - INTERVAL :days DAY, NOW(),
                                UTC_TIMESTAMP(3) - INTERVAL :days DAY, 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .setParameter("terminal", terminal)
                .setParameter("reason", rejectionReason)
                .setParameter("days", closedDaysAgo)
                .executeUpdate();
    }
}
