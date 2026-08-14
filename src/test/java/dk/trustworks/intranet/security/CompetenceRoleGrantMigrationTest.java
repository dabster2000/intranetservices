package dk.trustworks.intranet.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate that would have caught "I don't have access rights to admin module".
 *
 * <p>A permission introduced by a migration is inert until some role holds it at
 * {@code ALL} scope. V495 introduced {@code competence:read/write/approve} and granted
 * them to TECHPARTNER, TEAMLEAD and HR but not to ADMIN, on the reasoning that "ADMIN
 * reaches everything through admin:* expansion and needs no row here". That holds only
 * for the API-client path: {@link AdminScopeAugmentor} expands the identity minted from
 * the <em>bearer token</em>, which is a system/client token. The user-facing path —
 * {@code GET /users/{uuid}/permissions} &rarr; {@link EffectivePermissionService} &rarr;
 * {@link DbAuthzStore#loadEffectivePermissions} — is a plain three-table join with no
 * wildcard handling, and it is what the BFF's {@code requirePermission()} and the UI's
 * {@code RouteAccessGuard} consume. An ADMIN-only user therefore resolved to an empty
 * competence permission set and was redirected before any request left the browser.
 *
 * <p>The assertions are against <strong>what actually ships</strong> — the SQL literals
 * in the migrations — in the idiom of {@code PermissionSeedMigrationTest} and
 * {@code CompetenceSeedContentTest}. Reading the migrations rather than a Java constant
 * is the point: the defect was a missing row in a file, and only the file can prove it
 * is there. Fast tier, so it gates every deploy.
 */
@DisplayName("Competence role grants (V495 + V497)")
class CompetenceRoleGrantMigrationTest {

    private static final Path V495 = Path.of(
            "src/main/resources/db/migration/V495__Competence_module_ski_7b.sql");
    private static final Path V497 = Path.of(
            "src/main/resources/db/migration/V497__Competence_admin_role_grants.sql");

    /**
     * The keys the competence BFF routes gate on, from spec §7 / §19.12. Pinned
     * literally rather than derived: this is the frontend's contract with the catalogue
     * and the whole point is to fail when the seed drifts away from it.
     *
     * <p>{@code competence:approve} additionally gates the {@code competence-admin}
     * {@code page_registry} row, which is what {@code RouteAccessGuard} reads — deny
     * there and the user never reaches a BFF route at all.
     */
    private static final Set<String> BFF_GATED_KEYS =
            Set.of("competence:write", "competence:approve");

    /** permission_key -> role -> data scopes granted, as the two migrations write them. */
    private static Map<String, Map<String, Set<String>>> grants;

    /** The competence keys V495 inserts into {@code permission}. */
    private static Set<String> moduleKeys;

    @BeforeAll
    static void parseMigrations() throws IOException {
        assertTrue(Files.exists(V495), "Missing " + V495);
        assertTrue(Files.exists(V497),
                "Missing " + V497 + " — ADMIN's competence grants live there. V495 cannot be "
                        + "edited: it has already run, and repair-at-start realigns a changed "
                        + "checksum WITHOUT re-executing the file.");

        String sql495 = Files.readString(V495, StandardCharsets.UTF_8);
        String sql497 = Files.readString(V497, StandardCharsets.UTF_8);

        moduleKeys = competenceKeysDeclaredIn(sql495);
        grants = new LinkedHashMap<>();
        collectGrants(sql495, grants);
        collectGrants(sql497, grants);
    }

    @Test
    @DisplayName("V495 still declares the three competence permission keys")
    void moduleDeclaresThreeKeys() {
        assertTrue(moduleKeys.containsAll(
                        List.of("competence:read", "competence:write", "competence:approve")),
                "V495's INSERT INTO permission no longer declares all three competence keys; "
                        + "found " + moduleKeys);
    }

    /**
     * The general form of the defect: a key nobody holds at ALL scope is a key no user can
     * exercise, because {@code loadEffectivePermissions} filters on {@code data_scope='ALL'}.
     */
    @Test
    @DisplayName("every competence permission is held by at least one role at ALL scope")
    void everyModuleKeyIsGrantedSomewhereAtAllScope() {
        for (String key : moduleKeys) {
            Set<String> holders = rolesHolding(key, "ALL");
            assertFalse(holders.isEmpty(),
                    "No role holds " + key + " at ALL scope. EffectivePermissionService returns "
                            + "ALL-scope grants only, so no user would ever resolve to this "
                            + "permission and every gate consuming it — the BFF's "
                            + "requirePermission() and the UI's RouteAccessGuard — denies "
                            + "unconditionally. Grants seen: " + describe(key));
        }
    }

    /**
     * The specific regression. Fails without V497.
     */
    @Test
    @DisplayName("ADMIN holds all three competence keys at ALL scope (regression: V497)")
    void adminHoldsEveryCompetenceKeyAtAllScope() {
        for (String key : List.of("competence:read", "competence:write", "competence:approve")) {
            assertTrue(rolesHolding(key, "ALL").contains("ADMIN"),
                    "ADMIN does not hold " + key + " at ALL scope. admin:* is a token scope "
                            + "expanded by AdminScopeAugmentor onto the API client's identity; it "
                            + "is NOT a role_permission row and the user-permission join never "
                            + "expands it. ADMIN must be granted explicitly, as V486 does for "
                            + "recruitment:manage. Grants seen: " + describe(key));
        }
    }

    /**
     * Every key a competence BFF route gates on must be reachable by the module's
     * administrative role, or the admin UI is inert for the people who own it.
     */
    @Test
    @DisplayName("every BFF-gated competence key is reachable by ADMIN")
    void bffGatedKeysAreReachableByAdmin() {
        for (String key : BFF_GATED_KEYS) {
            assertTrue(moduleKeys.contains(key),
                    "BFF routes gate on " + key + " but V495 never declared it as a permission; "
                            + "role_permission.permission_key is an FK onto permission.");
            assertTrue(rolesHolding(key, "ALL").contains("ADMIN"),
                    "BFF routes gate on " + key + " and ADMIN cannot satisfy it. Grants seen: "
                            + describe(key));
        }
    }

    /**
     * Guards the deliberate non-change. TEAMLEAD is TEAM-scoped by design (spec §4.8 — the
     * matrix is colleagues' performance data). If a later migration quietly promotes it to
     * ALL to "make the admin page open", that is a company-wide widening and should be an
     * explicit owner decision, not a side effect.
     */
    @Test
    @DisplayName("TEAMLEAD's competence grants stay TEAM-scoped")
    void teamleadStaysTeamScoped() {
        for (String key : List.of("competence:read", "competence:approve")) {
            assertFalse(rolesHolding(key, "ALL").contains("TEAMLEAD"),
                    "TEAMLEAD was granted " + key + " at ALL scope. Spec §4.8 scopes team leads "
                            + "to TEAM because the matrix is colleagues' training records. "
                            + "Widening it is an owner decision. Grants seen: " + describe(key));
        }
    }

    // ---------------------------------------------------------------
    // parsing
    // ---------------------------------------------------------------

    /**
     * {@code ('ADMIN', 'competence:read', 'ALL', NOW(), 'V497')} — anchored on the
     * competence key in the second position, which is what keeps the neighbouring
     * {@code INSERT INTO role_definition} tuples (whose second column is a display label)
     * out of the result.
     */
    private static final Pattern GRANT = Pattern.compile(
            "\\(\\s*'([A-Z_]+)'\\s*,\\s*'(competence:[a-z]+)'\\s*,\\s*'([A-Z]+)'");

    private static void collectGrants(String sql, Map<String, Map<String, Set<String>>> into) {
        for (String statement : rolePermissionStatements(sql)) {
            Matcher m = GRANT.matcher(statement);
            while (m.find()) {
                into.computeIfAbsent(m.group(2), k -> new LinkedHashMap<>())
                        .computeIfAbsent(m.group(1), r -> new LinkedHashSet<>())
                        .add(m.group(3));
            }
        }
    }

    /**
     * The {@code INSERT INTO role_permission ... ;} statements, so a grant-shaped tuple
     * sitting in a comment or in some other table's insert cannot be mistaken for a live
     * grant. Comment lines are stripped first: V497's header quotes the very SQL this
     * parser looks for, and counting a rollback snippet as a grant would make the gate
     * pass on prose.
     */
    private static List<String> rolePermissionStatements(String sql) {
        return statements(stripComments(sql)).stream()
                .filter(s -> s.toLowerCase().contains("insert into role_permission"))
                .toList();
    }

    private static Set<String> competenceKeysDeclaredIn(String sql) {
        Set<String> keys = new TreeSet<>();
        for (String statement : statements(stripComments(sql))) {
            if (!statement.toLowerCase().contains("insert into permission")) continue;
            Matcher m = Pattern.compile("'(competence:[a-z]+)'").matcher(statement);
            while (m.find()) keys.add(m.group(1));
        }
        return keys;
    }

    /**
     * Split on {@code ;}. Adequate here and only here: both files' competence blocks are
     * plain INSERTs. V495 does contain {@code DELIMITER $$} trigger bodies, but they carry
     * no {@code competence:} literal and no {@code insert into role_permission}, so the
     * fragments they split into are filtered out before anything is read from them.
     */
    private static List<String> statements(String sql) {
        return List.of(sql.split(";"));
    }

    private static String stripComments(String sql) {
        return sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    private static Set<String> rolesHolding(String key, String scope) {
        Set<String> roles = new TreeSet<>();
        grants.getOrDefault(key, Map.of())
                .forEach((role, scopes) -> {
                    if (scopes.contains(scope)) roles.add(role);
                });
        return roles;
    }

    private static String describe(String key) {
        Map<String, Set<String>> byRole = grants.get(key);
        return byRole == null ? "(none)" : byRole.toString();
    }
}
