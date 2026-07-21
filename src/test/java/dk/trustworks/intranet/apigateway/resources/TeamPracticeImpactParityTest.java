package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.TeamPracticeAssignment;
import dk.trustworks.intranet.model.UserPracticeHistory;
import dk.trustworks.intranet.services.PracticeService;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code GET /teams/{uuid}/practice/impact} to the cascade it previews, and
 * covers what a practice change actually writes.
 * <p>
 * <b>Why the parity test exists.</b> The impact preview and the cascade are two
 * copies of one predicate living in two files: the endpoint's member query in
 * {@code TeamResource}/{@code TeamService}, and
 * {@code PracticeSyncService.applyTeamPracticeChange}'s
 * {@code teammembertype = MEMBER and startdate <= :date and (enddate is null or
 * enddate > :date)}. Nothing in the type system ties them together, so they can
 * drift silently — and the drift is invisible in production: the modal would
 * name a set of people, the write would touch a different one, and the only
 * symptom is practice history quietly attributed to the wrong consultants. The
 * two existing team-member endpoints are both already wrong for this purpose
 * ({@code /users} ignores dates entirely and returns 31 for a 9-member cascade;
 * {@code /users/search/findByMonth} adds status and consultant-type filters the
 * cascade does not apply), which is exactly the drift this test forbids.
 * <p>
 * The seed therefore contains one row per way the two predicates could disagree.
 */
@QuarkusTest
class TeamPracticeImpactParityTest {

    private static final String ACTOR = "00000000-0000-0000-0000-0000000000ad";
    private static final LocalDate TODAY = LocalDate.now();

    @Inject
    EntityManager em;

    @Inject
    PracticeService practiceService;

    private final List<String> teams = new ArrayList<>();
    private final List<String> users = new ArrayList<>();

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!users.isEmpty()) {
                UserPracticeHistory.delete("useruuid in ?1", users);
                UserStatus.delete("useruuid in ?1", users);
            }
            for (String teamuuid : teams) {
                TeamRole.delete("teamuuid", teamuuid);
                TeamPracticeAssignment.delete("teamUuid", teamuuid);
            }
            if (!users.isEmpty()) User.delete("uuid in ?1", users);
            for (String teamuuid : teams) Team.delete("uuid", teamuuid);
        });
        users.clear();
        teams.clear();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:read", "teams:write"})
    void the_impact_preview_is_exactly_the_set_the_cascade_re_derives() {
        String teamuuid = persistTeam("Parity Team");

        // Included: a plain current MEMBER.
        String currentMember = persistUser("Current", "Member", "ACTIVE");
        persistRole(teamuuid, currentMember, TeamMemberType.MEMBER, TODAY.minusDays(30), null);

        // Included: a current MEMBER whose user is TERMINATED. The cascade applies
        // NO status filter, so this person's practice history is rewritten — the
        // preview must say so. findByMonth would hide them; that is the bug.
        String terminatedMember = persistUser("Terminated", "Member", "TERMINATED");
        persistRole(teamuuid, terminatedMember, TeamMemberType.MEMBER, TODAY.minusDays(30), null);

        // Excluded: the membership already ended (half-open [start, end) — an
        // enddate of today is already out).
        String endedMember = persistUser("Ended", "Member", "ACTIVE");
        persistRole(teamuuid, endedMember, TeamMemberType.MEMBER, TODAY.minusDays(100), TODAY.minusDays(1));

        // Excluded: the membership has not started yet.
        String futureMember = persistUser("Future", "Member", "ACTIVE");
        persistRole(teamuuid, futureMember, TeamMemberType.MEMBER, TODAY.plusDays(10), null);

        // Excluded: practice derives from MEMBER roles only.
        String leader = persistUser("Team", "Leader", "ACTIVE");
        persistRole(teamuuid, leader, TeamMemberType.LEADER, TODAY.minusDays(30), null);
        String sponsor = persistUser("Team", "Sponsor", "ACTIVE");
        persistRole(teamuuid, sponsor, TeamMemberType.SPONSOR, TODAY.minusDays(30), null);

        JsonPath impact = given()
                .header("X-Requested-By", ACTOR)
        .when()
                .get("/teams/" + teamuuid + "/practice/impact")
        .then()
                .statusCode(200)
                .extract().jsonPath();

        Set<String> previewed = new HashSet<>(impact.getList("members.useruuid", String.class));
        assertEquals(Set.of(currentMember, terminatedMember), previewed,
                "the preview must be the current-MEMBER set, terminated users included");
        assertEquals(previewed.size(), impact.getInt("affectedCount"),
                "affectedCount must count the members it lists");
        assertEquals(teamuuid, impact.getString("teamuuid"));
        assertEquals("Parity Team", impact.getString("teamName"));

        String code = anActivePracticeCode();
        putPractice(teamuuid, "{\"practiceCode\":\"" + code + "\"}");

        // Every user the cascade touched left a history row stamped with this
        // team as the source — and the team is minted by this test, so the set is
        // exactly this cascade's.
        Set<String> cascaded = inTx(() -> UserPracticeHistory
                .<UserPracticeHistory>list("sourceTeamUuid = ?1", teamuuid)
                .stream().map(UserPracticeHistory::getUseruuid).collect(Collectors.toSet()));

        assertEquals(previewed, cascaded, "the impact preview must equal the cascade set exactly");
        assertTrue(cascaded.contains(terminatedMember), "a TERMINATED current MEMBER is cascaded to");
        assertTrue(cascaded.stream().noneMatch(
                        u -> u.equals(endedMember) || u.equals(futureMember) || u.equals(leader) || u.equals(sponsor)),
                "ended, future-dated, LEADER and SPONSOR rows must be cascaded to by neither side");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:read", "teams:write"})
    void a_practice_change_writes_the_assignment_row_and_re_derives_the_members() {
        String teamuuid = persistTeam("Cascade Team");
        String member = persistUser("Cascade", "Member", "ACTIVE");
        persistRole(teamuuid, member, TeamMemberType.MEMBER, TODAY.minusDays(30), null);

        String code = anActivePracticeCode();
        String practiceUuid = practiceUuidOf(code);
        putPractice(teamuuid, "{\"practiceCode\":\"" + code + "\"}");

        assertEquals(practiceUuid, inTx(() -> Team.<Team>findById(teamuuid).getPracticeUuid()),
                "the team's denormalized practice key must follow the change");

        TeamPracticeAssignment assignment = inTx(() -> TeamPracticeAssignment
                .<TeamPracticeAssignment>find("teamUuid = ?1 and enddate is null", teamuuid).firstResult());
        assertNotNull(assignment, "the transition must be recorded in team_practice_assignment");
        assertEquals(practiceUuid, assignment.getPracticeUuid());
        assertEquals(TODAY, assignment.getStartdate(), "the change is effective today (D3)");

        assertEquals(practiceUuid, inTx(() -> User.<User>findById(member).getPracticeUuid()),
                "the member's practice must be re-derived");
        UserPracticeHistory open = openHistory(member);
        assertNotNull(open, "the transition must leave an open history row");
        assertEquals(practiceUuid, open.getPracticeUuid());
        assertEquals(TODAY, open.getEffectiveFrom());
        assertEquals(teamuuid, open.getSourceTeamUuid(), "provenance names the driving team");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:read", "teams:write"})
    void clearing_the_practice_derives_the_members_to_null() {
        String teamuuid = persistTeam("Clearing Team");
        String member = persistUser("Clearing", "Member", "ACTIVE");
        persistRole(teamuuid, member, TeamMemberType.MEMBER, TODAY.minusDays(30), null);

        putPractice(teamuuid, "{\"practiceCode\":\"" + anActivePracticeCode() + "\"}");
        putPractice(teamuuid, "{\"practiceCode\":null}");

        assertNull(inTx(() -> Team.<Team>findById(teamuuid).getPracticeUuid()),
                "the team must end up practice-less");
        assertNull(inTx(() -> User.<User>findById(member).getPracticeUuid()),
                "members of a practice-less team derive to NULL — no sentinel");
        UserPracticeHistory open = openHistory(member);
        assertNotNull(open, "a NULL period is a first-class history row, not an absence of one");
        assertNull(open.getPracticeUuid());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void putPractice(String teamuuid, String body) {
        given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
        .when()
                .put("/teams/" + teamuuid + "/practice")
        .then()
                .statusCode(200);
    }

    private String persistTeam(String name) {
        String teamuuid = UUID.randomUUID().toString();
        String shortname = freeShortname();
        QuarkusTransaction.requiringNew().run(() -> {
            Team team = new Team();
            team.setUuid(teamuuid);
            team.setName(name);
            team.setShortname(shortname);
            team.setDescription("seeded by TeamPracticeImpactParityTest");
            Team.persist(team);
        });
        teams.add(teamuuid);
        return teamuuid;
    }

    /** Seeds a user plus one userstatus row, so the status-filtering endpoints see a real state. */
    private String persistUser(String firstname, String lastname, String status) {
        String useruuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                    INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                      created, cpr, birthday)
                    VALUES (:uuid, :first, :last, :email, :username, 'x', 'CONSULTANT',
                            NOW(), '0000000000', '2000-01-01')
                    """)
                    .setParameter("uuid", useruuid)
                    .setParameter("first", firstname)
                    .setParameter("last", lastname)
                    .setParameter("email", useruuid + "@example.com")
                    .setParameter("username", useruuid)
                    .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO userstatus (uuid, useruuid, companyuuid, status, allocation, statusdate, type,
                                           created_at, updated_at, created_by)
                    VALUES (:uuid, :user, :company, :status, 1, :statusdate, 'CONSULTANT',
                            NOW(), NOW(), 'parity-test')
                    """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("user", useruuid)
                    .setParameter("company", anyCompanyUuid())
                    .setParameter("status", status)
                    .setParameter("statusdate", TODAY.minusYears(1))
                    .executeUpdate();
        });
        users.add(useruuid);
        return useruuid;
    }

    private void persistRole(String teamuuid, String useruuid, TeamMemberType type,
                             LocalDate startdate, LocalDate enddate) {
        // Persisted directly: TeamService.addTeamroleToUser enforces the
        // single-MEMBER invariant and fires its own practice sync, neither of
        // which this fixture wants.
        QuarkusTransaction.requiringNew().run(() -> TeamRole.persist(
                new TeamRole(UUID.randomUUID().toString(), teamuuid, useruuid, startdate, enddate, type)));
    }

    private UserPracticeHistory openHistory(String useruuid) {
        return inTx(() -> UserPracticeHistory
                .<UserPracticeHistory>find("useruuid = ?1 and effectiveTo is null", useruuid).firstResult());
    }

    /** The table is {@code companies} (plural) — {@code company} does not exist. */
    private String anyCompanyUuid() {
        return (String) em.createNativeQuery("SELECT uuid FROM companies LIMIT 1").getSingleResult();
    }

    private String anActivePracticeCode() {
        List<String> codes = inTx(() -> practiceService.activePracticeCodes());
        if (codes.isEmpty()) throw new IllegalStateException("registry has no active practice to test with");
        return codes.getFirst();
    }

    private String practiceUuidOf(String code) {
        return inTx(() -> practiceService.resolveByIdOrCode(code).getUuid());
    }

    /** team.shortname is varchar(4) and unique — a literal would collide with live data. */
    private String freeShortname() {
        String pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder candidate = new StringBuilder("Y");
            for (int i = 0; i < 3; i++) candidate.append(pool.charAt(ThreadLocalRandom.current().nextInt(pool.length())));
            String shortname = candidate.toString();
            if (inTx(() -> Team.count("shortname = ?1", shortname)) == 0L) return shortname;
        }
        throw new IllegalStateException("no free 4-character shortname found");
    }

    private static <T> T inTx(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
