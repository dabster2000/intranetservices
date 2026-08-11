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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end authorization on the two position <em>mutations</em> —
 * {@code PUT /recruitment/positions/{uuid}} and
 * {@code POST /recruitment/positions/{uuid}/close}.
 *
 * <p>Go-live spec §3, row "Positions — create/edit/close": ADMIN, HR and
 * RECRUITMENT everywhere; {@code TEAMLEAD} <b>own only</b> — hiring owner,
 * current lead of the position's team, or a member of its circle.
 *
 * <p>The regression this pins: go-live decision D3 put {@code TEAMLEAD} into
 * {@code POSITION_READ_ROLES}, so every teamlead reads every non-partner
 * position. Both endpoints gated on read visibility alone, which silently
 * turned the read tier into a company-wide write tier — any of the 20 role
 * holders could rename or close any non-partner position. The unit-level
 * twin of these cases lives in
 * {@code RecruitmentVisibilityIntegrationTest#positionMutation_*}.
 *
 * <p>Codes are deliberate: <b>403</b> here, not 404. The caller cleared the
 * read check, so the position's existence is not a secret from them —
 * unlike a partner-track position outside the circle, which stays 404.
 */
@QuarkusTest
class RecruitmentPositionMutationAuthzApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";
    private static final String POSITION = "/recruitment/positions/{uuid}";
    private static final String CLOSE = "/recruitment/positions/{uuid}/close";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String teamUuid;

    private String recruiterUser;   // HR role — recruiter tier, no involvement
    private String teamleadUser;    // TEAMLEAD role + current LEADER of teamUuid
    private String otherTeamlead;   // TEAMLEAD role, leads nothing, owns nothing
    private String staffOwnerUser;  // hiring owner of the staff position, no roles

    private String practicePositionUuid; // PRACTICE_TEAM on practiceUuid + teamUuid
    private String staffPositionUuid;    // STAFF_ROLE owned by staffOwnerUser
    private String partnerPositionUuid;  // PARTNER, circle = recruiterUser

    private String previousFlagValue;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        otherTeamlead = UUID.randomUUID().toString();
        staffOwnerUser = UUID.randomUUID().toString();
        practicePositionUuid = UUID.randomUUID().toString();
        staffPositionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            insertUser(recruiterUser, "Rina", "Recruiter");
            insertUser(teamleadUser, "Tim", "Teamlead");
            insertUser(otherTeamlead, "Ove", "Otherlead");
            insertUser(staffOwnerUser, "Olga", "Owner");
            insertRole(recruiterUser, "HR");
            insertRole(teamleadUser, "TEAMLEAD");
            insertRole(otherTeamlead, "TEAMLEAD");
            insertPractice(practiceUuid);
            // A real team row: RecruitmentPositionService.validateTeamExists
            // rejects a PUT whose teamUuid has no team (400 before authz).
            insertTeam(teamUuid, practiceUuid);
            insertTeamLeader(teamleadUser, teamUuid);

            insertPosition(practicePositionUuid, "Consultant", "PRACTICE_TEAM",
                    practiceUuid, teamUuid, null);
            insertPosition(staffPositionUuid, "Office manager", "STAFF_ROLE",
                    null, null, staffOwnerUser);
            insertPosition(partnerPositionUuid, "Partner hire", "PARTNER",
                    null, null, null);
            insertCircleMember(partnerPositionUuid, recruiterUser);

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
            List<String> positions = List.of(practicePositionUuid, staffPositionUuid,
                    partnerPositionUuid);
            List<String> users = List.of(recruiterUser, teamleadUser, otherTeamlead, staffOwnerUser);
            em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positions).executeUpdate();
            em.createNativeQuery("DELETE FROM teamroles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :u")
                    .setParameter("u", users).executeUpdate();
            em.createNativeQuery("DELETE FROM team WHERE uuid = :t")
                    .setParameter("t", teamUuid).executeUpdate();
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

    // ---- The hole: read access is not write access ---------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void teamleadWithoutInvolvement_readsThePosition_butCannotEditIt() {
        // D3 read access is intact — the position is not hidden from them.
        given().header("X-Requested-By", otherTeamlead)
                .when().get(POSITION, staffPositionUuid)
                .then().statusCode(200);

        given().header("X-Requested-By", otherTeamlead)
                .contentType(ContentType.JSON)
                .body(renameBody("Office manager (hijacked)", "STAFF_ROLE", staffOwnerUser))
                .when().put(POSITION, staffPositionUuid)
                .then().statusCode(403);

        assertEquals("Office manager", storedTitle(staffPositionUuid),
                "a refused edit must not reach the database");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void teamleadWithoutInvolvement_cannotCloseSomeoneElsesPosition() {
        given().header("X-Requested-By", otherTeamlead)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when().post(CLOSE, practicePositionUuid)
                .then().statusCode(403);

        assertEquals("OPEN", storedStatus(practicePositionUuid),
                "a refused close must leave the position open");
    }

    // ---- Own only: the three involvement routes still work ---------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void currentLeadOfThePositionsTeam_mayEditIt() {
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(renameBody("Consultant (renamed)", "PRACTICE_TEAM", null))
                .when().put(POSITION, practicePositionUuid)
                .then().statusCode(200);

        assertEquals("Consultant (renamed)", storedTitle(practicePositionUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void hiringOwner_mayCloseTheirOwnPosition() {
        given().header("X-Requested-By", staffOwnerUser)
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when().post(CLOSE, staffPositionUuid)
                .then().statusCode(200);

        assertEquals("CLOSED", storedStatus(staffPositionUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void recruiterTier_mayEditAnyNonPartnerPosition() {
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(renameBody("Office manager (HR edit)", "STAFF_ROLE", staffOwnerUser))
                .when().put(POSITION, staffPositionUuid)
                .then().statusCode(200);

        assertEquals("Office manager (HR edit)", storedTitle(staffPositionUuid));
    }

    // ---- Partner track: unchanged by the new gate ------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerPositionOutsideTheCircle_stays404_notForbidden() {
        // Existence must not leak: the read check fires first and hides it.
        given().header("X-Requested-By", teamleadUser)
                .contentType(ContentType.JSON)
                .body(renameBody("Partner hire (hijacked)", "PARTNER", null))
                .when().put(POSITION, partnerPositionUuid)
                .then().statusCode(404);

        assertEquals("Partner hire", storedTitle(partnerPositionUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerCircleRecruiter_mayStillEdit() {
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(renameBody("Partner hire (renamed)", "PARTNER", null))
                .when().put(POSITION, partnerPositionUuid)
                .then().statusCode(200);

        assertEquals("Partner hire (renamed)", storedTitle(partnerPositionUuid));
    }

    // ---- Helpers ---------------------------------------------------------------------

    /** Full PUT shape — the dialog always sends every field (PositionRequest javadoc). */
    private Map<String, Object> renameBody(String title, String track, String hiringOwnerUuid) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("hiringTrack", track);
        if ("PRACTICE_TEAM".equals(track)) {
            body.put("practiceUuid", practiceUuid);
            body.put("teamUuid", teamUuid);
        }
        if (hiringOwnerUuid != null) {
            body.put("hiringOwnerUuid", hiringOwnerUuid);
        }
        return body;
    }

    /** Read in a FRESH transaction — the resource's own response would beg the question. */
    private String storedTitle(String positionUuid) {
        return scalar("SELECT title FROM recruitment_positions WHERE uuid = :u", positionUuid);
    }

    private String storedStatus(String positionUuid) {
        return scalar("SELECT status FROM recruitment_positions WHERE uuid = :u", positionUuid);
    }

    private String scalar(String sql, String positionUuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                (String) em.createNativeQuery(sql)
                        .setParameter("u", positionUuid)
                        .getSingleResult());
    }

    private void insertUser(String uuid, String firstName, String lastName) {
        em.createNativeQuery("""
                        INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                          created, cpr, birthday)
                        VALUES (:uuid, :first, :last, :email, :username, 'x', 'CONSULTANT',
                                NOW(), '0000000000', '2000-01-01')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("first", firstName)
                .setParameter("last", lastName)
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
                        VALUES (:code, :uuid, 'Mutation Fixture', 1, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "M" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    private void insertTeam(String uuid, String practiceUuid) {
        em.createNativeQuery("""
                        INSERT INTO team (uuid, name, shortname, practice_uuid)
                        VALUES (:uuid, 'Mutation Fixture Team', 'MFT', :practice)
                        """)
                .setParameter("uuid", uuid)
                .setParameter("practice", practiceUuid)
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

    private void insertPosition(String uuid, String title, String track, String practiceUuid,
                                String teamUuid, String hiringOwnerUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, team_uuid, hiring_owner_uuid,
                             stage_set, demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice, :team, :owner,
                                '["SCREENING","INTERVIEW_1","OFFER","HIRED"]',
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("team", teamUuid)
                .setParameter("owner", hiringOwnerUuid)
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
}
