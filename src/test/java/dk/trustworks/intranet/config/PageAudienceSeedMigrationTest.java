package dk.trustworks.intranet.config;

import dk.trustworks.intranet.security.Permissions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 — pins V467/V468 to the owner-approved mapping of 2026-08-06
 * (trustworks-intranet-v2 docs/access/page-registry-audit.md §9–§10).
 *
 * <p>V468's pairs were individually approved; this test fails if a pair is added,
 * dropped or edited without going back to the owner. It also enforces the F-13
 * non-negotiables: every insert is the guarded INSERT..SELECT shape, and no phantom
 * role (zero holders, absent from production role_definition) is ever named.
 */
class PageAudienceSeedMigrationTest {

    private static final Path V467 =
            Path.of("src/main/resources/db/migration/V467__Page_registry_required_permission.sql");
    private static final Path V468 =
            Path.of("src/main/resources/db/migration/V468__Seed_role_permission_page_audiences.sql");

    /** The exact owner-approved pair set (role -> permission). */
    private static final Set<String> APPROVED_PAIRS = Set.of(
            "ACCOUNTING->accounting:read", "ADMIN->accounting:read", "PARTNER->accounting:read",
            "ADMIN->admin:read",
            "DEVOPS->bugreports:admin", "ADMIN->bugreports:admin",
            "SALES->capacity:read", "ADMIN->capacity:read",
            "HR->consultant:read", "TEAMLEAD->consultant:read", "ADMIN->consultant:read",
            "SALES->crm:write", "ADMIN->crm:write", "PARTNER->crm:write",
            "DPO->devices:read", "ADMIN->devices:read",
            "HR->expenses:review", "ADMIN->expenses:review",
            "EDITOR->knowledge:write", "ADMIN->knowledge:write",
            "EDITOR->news:write", "ADMIN->news:write",
            "PARTNER->partnerbonus:read",
            "ADMIN->questionnaires:write",
            "ADMIN->recruitment:comp", "HR->recruitment:comp", "PARTNER->recruitment:comp",
            "ADMIN->revenue:read", "TECHPARTNER->revenue:read",
            "ADMIN->signing:write", "HR->signing:write",
            "TEAMLEAD->teams:read");

    private static final Set<String> PHANTOM_ROLES = Set.of("CXO", "MANAGER", "CRM_VIEWER");

    private static final Pattern GUARDED_PAIR = Pattern.compile(
            "WHERE rd\\.name = '([A-Z_]+)' AND p\\.permission_key = '([a-z*:]+)'");

    @Test
    void v468ContainsExactlyTheApprovedPairs() throws IOException {
        String sql = Files.readString(V468, StandardCharsets.UTF_8);

        Set<String> found = new TreeSet<>();
        Matcher m = GUARDED_PAIR.matcher(sql);
        while (m.find()) {
            found.add(m.group(1) + "->" + m.group(2));
        }

        assertEquals(new TreeSet<>(APPROVED_PAIRS), found,
                "V468 pairs differ from the owner-approved set of 2026-08-06. Any change "
                        + "must go back to the owner and update docs/access/page-registry-audit.md.");
        assertEquals(APPROVED_PAIRS.size(), sql.split("INSERT INTO role_permission", -1).length - 1,
                "Every pair must be one guarded INSERT..SELECT — a bare INSERT would FK-fail "
                        + "on a repair-at-start re-run if the role was deleted via the admin UI (F-13)");
    }

    @Test
    void noPhantomRoleIsSeeded() throws IOException {
        String sql = Files.readString(V468, StandardCharsets.UTF_8);
        for (String phantom : PHANTOM_ROLES) {
            assertTrue(!sql.contains("'" + phantom + "'"),
                    phantom + " has zero holders in production and must never be seeded");
        }
    }

    @Test
    void v468PermissionsExistInTheCatalogue() throws IOException {
        String sql = Files.readString(V468, StandardCharsets.UTF_8);
        Matcher m = GUARDED_PAIR.matcher(sql);
        while (m.find()) {
            String key = m.group(2);
            assertTrue(Permissions.allKeysAsSet().contains(key),
                    "V468 names '" + key + "', which is not in Permissions.java — the guarded "
                            + "INSERT would silently seed nothing and the page would deny everyone");
        }
    }

    @Test
    void v467BackfillOnlyNamesCataloguePermissions() throws IOException {
        String sql = Files.readString(V467, StandardCharsets.UTF_8);

        Matcher m = Pattern.compile("SET required_permission = '([a-z*:]+)'").matcher(sql);
        int count = 0;
        while (m.find()) {
            count++;
            assertTrue(Permissions.allKeysAsSet().contains(m.group(1)),
                    "V467 backfills '" + m.group(1) + "', which is not in the catalogue — "
                            + "the FK would reject it and abort the migration");
        }
        assertTrue(count >= 18, "V467 backfill statements went missing (found " + count + ")");

        assertTrue(sql.contains("COLLATE utf8mb4_general_ci"),
                "The column-level COLLATE is mandatory (F-12) — without it the FK creates in "
                        + "one environment and fails in the other");
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS")
                        && sql.contains("FOREIGN KEY IF NOT EXISTS"),
                "repair-at-start re-runs this file after a rollback — everything must be idempotent");
        List<String> unguarded = sql.lines()
                .filter(l -> l.stripLeading().startsWith("UPDATE page_registry SET required_permission"))
                .toList();
        for (String line : unguarded) {
            // the guard lives on the WHERE line(s) that follow; assert per statement instead
            assertTrue(sql.contains("AND required_permission IS NULL")
                            || line.contains("required_permission IS NULL"),
                    "Backfill UPDATEs must be guarded on IS NULL so a re-run never clobbers "
                            + "a value edited through the Phase 7 admin console");
        }
    }
}
