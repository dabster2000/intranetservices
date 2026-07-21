package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.model.TeamPracticeAssignment;
import dk.trustworks.intranet.services.PracticeService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Endpoint contract for the team admin writes (POST /teams, PUT /teams/{uuid}).
 * Driven over HTTP rather than through the resource bean: the status codes ARE
 * the contract (hand-rolled {@code BadRequestException} → 400 via
 * {@code WebApplicationExceptionMapper}, {@code NotFoundException} → 404), and
 * the wire is the only place the "server mints the uuid" rule is observable.
 * <p>
 * Seeded rows are committed (an HTTP call runs in its own transaction, so
 * {@code @TestTransaction} would hide them from the server) and removed in
 * {@link #cleanup()}.
 */
@QuarkusTest
class TeamAdminResourceTest {

    /** X-Requested-By: the acting user the audit columns and history provenance record. */
    private static final String ACTOR = "00000000-0000-0000-0000-0000000000ad";

    @Inject
    PracticeService practiceService;

    private final List<String> teams = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String teamuuid : teams) QuarkusTransaction.requiringNew().run(() -> {
            TeamPracticeAssignment.delete("teamUuid", teamuuid);
            Team.delete("uuid", teamuuid);
        });
        teams.clear();
    }

    // ── POST /teams ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_mints_the_uuid_server_side_and_ignores_a_client_supplied_one() {
        // Team has no @GeneratedValue/@PrePersist, so the resource mints the uuid.
        // A client-supplied one is a mass-assignment hazard: it must not land.
        String clientSupplied = UUID.randomUUID().toString();
        String shortname = freeShortname();
        Map<String, Object> body = new HashMap<>();
        body.put("uuid", clientSupplied);
        body.put("name", "Team " + shortname);
        body.put("shortname", shortname);
        body.put("description", "seeded by TeamAdminResourceTest");

        String location = given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
        .when()
                .post("/teams")
        .then()
                .statusCode(201)
                .extract().header("Location");

        assertNotNull(location, "create must answer with a Location header");
        String minted = location.substring(location.lastIndexOf('/') + 1);
        teams.add(minted);
        assertNotEquals(clientSupplied, minted, "the client-supplied uuid must be ignored");

        TeamSnapshot team = snapshot(minted);
        assertNotNull(team, "the minted uuid must address the persisted team");
        assertEquals("Team " + shortname, team.name());
        assertEquals(shortname, team.shortname());
        assertNull(snapshot(clientSupplied), "no team may exist under the client-supplied uuid");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_rejects_a_blank_name() {
        postExpecting(400, Map.of("name", "   ", "shortname", freeShortname()));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_rejects_a_blank_shortname() {
        postExpecting(400, Map.of("name", "Nameless badge", "shortname", "  "));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_rejects_a_shortname_wider_than_the_column() {
        // team.shortname is varchar(4) — not a typo; a 5th character would be
        // silently truncated (or 500 in strict mode) rather than rejected.
        postExpecting(400, Map.of("name", "Too wide a badge", "shortname", "ABCDE"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_rejects_a_duplicate_shortname() {
        String shortname = freeShortname();
        teams.add(create("First claimant", shortname, null));
        // shortname is unique at the application level (D6) — the second claim is a 400,
        // not a 500 from a constraint violation and not a silent second badge.
        postExpecting(400, Map.of("name", "Second claimant", "shortname", shortname));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_rejects_an_unknown_practice_code() {
        postExpecting(400, Map.of(
                "name", "Team with a ghost practice",
                "shortname", freeShortname(),
                "practiceCode", "NOSUCH"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void create_with_a_practice_records_the_temporal_assignment() {
        // The practice must be routed through applyTeamPracticeChange, not set on
        // the entity directly, or team_practice_assignment never gets its open row
        // and the team's practice has no history to attribute reporting to.
        String code = anActivePracticeCode();
        String teamuuid = create("Team with a practice", freeShortname(), code);
        teams.add(teamuuid);

        assertEquals(practiceUuidOf(code), snapshot(teamuuid).practiceUuid(),
                "the team's denormalized practice key must follow the code");
        AssignmentSnapshot open = openAssignment(teamuuid);
        assertNotNull(open, "an open team_practice_assignment row must be recorded");
        assertEquals(practiceUuidOf(code), open.practiceUuid());
        assertEquals(LocalDate.now(), open.startdate(), "the assignment starts today (D3 — no backdating)");
    }

    @Test
    @TestSecurity(user = "reader", roles = {"teams:read"})
    void create_requires_the_write_scope() {
        given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Unauthorized team", "shortname", "XXXX"))
        .when()
                .post("/teams")
        .then()
                .statusCode(403);
    }

    // ── PUT /teams/{teamuuid} ─────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"teams:read", "teams:write"})
    void update_touches_only_the_supplied_fields_and_never_the_practice() {
        // THE @DynamicUpdate regression guard. Team is @DynamicUpdate precisely so
        // an unrelated flush cannot revert practice_uuid; an edit that loads the
        // entity and writes every column would blank it and orphan the open
        // team_practice_assignment row — invisible on this endpoint's response,
        // visible only as reporting attribution silently going missing.
        String code = anActivePracticeCode();
        String shortname = freeShortname();
        String teamuuid = create("Original name", shortname, code);
        teams.add(teamuuid);

        given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Renamed team"))
        .when()
                .put("/teams/" + teamuuid)
        .then()
                .statusCode(200);

        TeamSnapshot team = snapshot(teamuuid);
        assertEquals("Renamed team", team.name(), "the supplied field must be written");
        assertEquals(shortname, team.shortname(), "an omitted field means 'leave alone', not 'clear'");
        assertEquals("seeded by TeamAdminResourceTest", team.description());
        assertEquals(practiceUuidOf(code), team.practiceUuid(),
                "a partial edit must not revert practice_uuid");
        assertNotNull(openAssignment(teamuuid),
                "the open assignment row must survive the edit that did not concern it");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void update_rejects_a_duplicate_shortname() {
        String taken = freeShortname();
        teams.add(create("Badge owner", taken, null));
        String teamuuid = create("Badge thief", freeShortname(), null);
        teams.add(teamuuid);

        given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("shortname", taken))
        .when()
                .put("/teams/" + teamuuid)
        .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"teams:write"})
    void update_of_an_unknown_team_is_404() {
        given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Ghost team"))
        .when()
                .put("/teams/" + UUID.randomUUID())
        .then()
                .statusCode(404);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Creates a team over the endpoint and returns the minted uuid. */
    private String create(String name, String shortname, String practiceCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("shortname", shortname);
        body.put("description", "seeded by TeamAdminResourceTest");
        if (practiceCode != null) body.put("practiceCode", practiceCode);

        String location = given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
        .when()
                .post("/teams")
        .then()
                .statusCode(201)
                .extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void postExpecting(int status, Map<String, Object> body) {
        given()
                .header("X-Requested-By", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
        .when()
                .post("/teams")
        .then()
                .statusCode(status);
    }

    private record TeamSnapshot(String name, String shortname, String description, String practiceUuid) {}

    private record AssignmentSnapshot(String practiceUuid, LocalDate startdate) {}

    private TeamSnapshot snapshot(String teamuuid) {
        return inTx(() -> {
            Team team = Team.findById(teamuuid);
            return team == null ? null : new TeamSnapshot(
                    team.getName(), team.getShortname(), team.getDescription(), team.getPracticeUuid());
        });
    }

    private AssignmentSnapshot openAssignment(String teamuuid) {
        return inTx(() -> {
            TeamPracticeAssignment open = TeamPracticeAssignment
                    .<TeamPracticeAssignment>find("teamUuid = ?1 and enddate is null", teamuuid).firstResult();
            return open == null ? null : new AssignmentSnapshot(open.getPracticeUuid(), open.getStartdate());
        });
    }

    private String anActivePracticeCode() {
        List<String> codes = inTx(() -> practiceService.activePracticeCodes());
        if (codes.isEmpty()) throw new IllegalStateException("registry has no active practice to test with");
        return codes.getFirst();
    }

    private String practiceUuidOf(String code) {
        return inTx(() -> practiceService.resolveByIdOrCode(code).getUuid());
    }

    /**
     * A 4-character badge no live team holds — the column is varchar(4) and the
     * shortname is unique, so a fixed literal would collide with real data.
     */
    private String freeShortname() {
        String pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder candidate = new StringBuilder("Z");
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
