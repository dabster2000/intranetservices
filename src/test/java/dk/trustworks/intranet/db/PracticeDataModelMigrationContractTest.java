package dk.trustworks.intranet.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the Part 1 practice migrations: V418 (practice registry +
 * team-settings, additive) and V419 (JK retirement, data-only). Reads the SQL
 * files and asserts the structural facts the frontend and the runtime code
 * depend on (tables, collation, FKs, seeds, display renames, budget seeds,
 * the user.practice default, page registrations and the JK data flips).
 */
class PracticeDataModelMigrationContractTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void creates_the_three_new_tables_with_matching_collation() throws IOException {
        String migration = read();

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS practice"), "must create practice");
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS practice_lead"), "must create practice_lead");
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS team_settings"), "must create team_settings");

        // All new tables must declare the team table's charset/collation so FK columns match.
        int collationCount = migration.split("COLLATE=utf8mb4_general_ci", -1).length - 1;
        assertTrue(collationCount >= 3,
                "practice / practice_lead / team_settings must each declare utf8mb4_general_ci (found " + collationCount + ")");
    }

    @Test
    void declares_the_foreign_keys_and_unique_keys() throws IOException {
        String migration = read();

        assertTrue(migration.contains("fk_practice_lead_practice"), "practice_lead FK to practice");
        assertTrue(migration.contains("fk_team_practice"), "team FK to practice");
        assertTrue(migration.contains("fk_team_settings_team"), "team_settings FK to team");
        assertTrue(migration.contains("uq_practice_display_code"), "unique display_code");
        assertTrue(migration.contains("uq_team_setting"), "unique (teamuuid, setting_key)");
    }

    @Test
    void seeds_all_seven_registry_rows_with_the_display_renames() throws IOException {
        String migration = read();

        for (String code : List.of("'PM'", "'SA'", "'BA'", "'DEV'", "'CYB'", "'UD'", "'JK'")) {
            assertTrue(migration.contains(code), "registry seed must include code " + code);
        }
        // Decision-7 display renames: SA->IA, BA->BU, DEV->TECH.
        assertTrue(migration.contains("'IA'"), "SA must display as IA");
        assertTrue(migration.contains("'BU'"), "BA must display as BU");
        assertTrue(migration.contains("'TECH'"), "DEV must display as TECH");
        // JK is a transitional inactive SEGMENT.
        assertTrue(migration.contains("'Junior Consultants (transitional)'"), "JK transitional name");
        assertTrue(migration.contains("INSERT IGNORE INTO practice"), "registry seed must be idempotent");
    }

    @Test
    void seeds_it_budget_preserving_todays_outcomes() throws IOException {
        String migration = read();

        assertTrue(migration.contains("'32000'"), "HiTech / Tech-it-or-Leave-it keep 32000");
        assertTrue(migration.contains("'0'"), "Junior Team seeded 0");
        assertTrue(migration.contains("'25000'"), "every other team seeded 25000");
        assertTrue(migration.contains("28b19c16-cd19-4ffb-bca1-e6245448f428"), "Junior Team UUID for the 0 seed");
        assertTrue(migration.contains("INSERT IGNORE INTO team_settings"), "budget seed must be idempotent");
    }

    @Test
    void drops_the_pm_default_on_user_practice() throws IOException {
        String migration = read();
        assertTrue(migration.contains("ALTER COLUMN practice SET DEFAULT 'UD'"),
                "user.practice default must become 'UD', not 'PM'");
    }

    @Test
    void registers_the_practices_settings_page() throws IOException {
        String migration = read();
        assertTrue(migration.contains("'settings-practices'"), "page_registry key settings-practices");
        assertTrue(migration.contains("ON DUPLICATE KEY UPDATE"), "page_registry upsert pattern");
    }

    @Test
    void migration_is_additive_only() throws IOException {
        String migration = read().toUpperCase();
        assertFalse(migration.contains("DROP TABLE"), "V418 must not drop tables");
        assertFalse(migration.contains("DROP COLUMN"), "V418 must not drop columns");
    }

    @Test
    void v419_flips_every_jk_reference_to_ud() throws IOException {
        String migration = readV419();

        assertTrue(migration.contains("UPDATE `user` SET practice = 'UD' WHERE practice = 'JK'"),
                "junior users must flip to UD");
        assertTrue(migration.contains("UPDATE user_practice_history SET practice = 'UD' WHERE practice = 'JK'"),
                "history sliver must be corrected");
        assertTrue(migration.contains("UPDATE sales_lead SET practice = 'UD' WHERE practice = 'JK'"),
                "sales-lead junior tag must be removed");
        assertTrue(migration.contains("DELETE FROM practice WHERE code = 'JK'"),
                "transitional JK registry row must be deleted");
    }

    @Test
    void v419_retires_practice_settings_data_and_jk_dashboard_page() throws IOException {
        String migration = readV419();

        assertTrue(migration.contains("DELETE FROM practice_settings"),
                "practice_settings data must be retired (table dropped in Part 2)");
        assertTrue(migration.contains("DELETE FROM page_registry WHERE page_key = 'jk-team-dashboard'"),
                "bespoke JK dashboard page entry must be removed");
    }

    @Test
    void v419_is_data_only() throws IOException {
        String migration = readV419().toUpperCase();
        assertFalse(migration.contains("DROP TABLE"), "V419 must not drop tables");
        assertFalse(migration.contains("ALTER TABLE"), "V419 must be data-only");
    }

    // ── Phase 0 migrations (V421–V423, spec §1.6.F) ───────────────────────

    @Test
    void v421_adds_the_audit_columns_idempotently() throws IOException {
        String migration = readV421();

        // practice has timestamps already — only the actor columns are added.
        assertTrue(migration.contains("ALTER TABLE practice"), "practice must gain actor columns");
        assertTrue(migration.contains("ALTER TABLE practice_lead"), "practice_lead must gain the full trail");
        assertTrue(migration.contains("ALTER TABLE team_settings"), "team_settings must gain creation columns");
        int guardedAdds = migration.split("ADD COLUMN IF NOT EXISTS", -1).length - 1;
        assertTrue(guardedAdds >= 8, "every ADD COLUMN must be IF NOT EXISTS-guarded (found " + guardedAdds + ")");
        assertTrue(migration.contains("WHERE created_at IS NULL") || migration.contains("WHERE created_by IS NULL"),
                "backfills must be guarded so re-runs are no-ops");
        assertFalse(migration.toUpperCase().contains("DROP "), "V421 is additive only");
    }

    @Test
    void v422_rederives_the_hygiene_row_sets_with_the_canonical_predicate() throws IOException {
        String migration = readV422();

        // Terminated users' open roles close at the latest statusdate <= today.
        assertTrue(migration.contains("us.status = 'TERMINATED'"), "termination derived from userstatus");
        assertTrue(migration.contains("us2.statusdate <= CURRENT_DATE"), "latest status must be capped at today");
        assertTrue(migration.contains("tr.enddate IS NULL"), "only open roles are touched");
        assertTrue(migration.contains("DELETE FROM teamroles WHERE useruuid IS NULL"), "orphan rows deleted");
        // Dual-membership resolution: ARBA row closes the day Cyber Security began.
        assertTrue(migration.contains("ab4ebf52-2203-4be6-a621-e0b4af847d88"), "dual-membership user targeted");
        assertTrue(migration.contains("'2024-03-01'"), "ARBA row closes at the CS startdate");
        // JK questionnaire strip is generic (JSON rebuild), not positional.
        assertTrue(migration.contains("JSON_TABLE"), "target_practices rebuilt as JSON, not string surgery");
        assertTrue(migration.contains("JSON_SEARCH"), "only rows containing JK are touched");
    }

    @Test
    void v423_drops_the_two_dead_views_idempotently() throws IOException {
        String migration = readV423();
        assertTrue(migration.contains("DROP VIEW IF EXISTS fact_historical_win_rates"));
        assertTrue(migration.contains("DROP VIEW IF EXISTS fact_revenue_runoff"));
        assertFalse(migration.toUpperCase().contains("DROP TABLE"), "V423 drops views only");
    }

    private static String read() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V418__Create_practice_registry_and_team_settings.sql"));
    }

    private static String readV419() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V419__Retire_jk_practice_data.sql"));
    }

    private static String readV421() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V421__Practice_phase0_audit_columns.sql"));
    }

    private static String readV422() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V422__Practice_phase0_team_role_hygiene.sql"));
    }

    private static String readV423() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V423__Drop_fact_historical_win_rates_and_fact_revenue_runoff.sql"));
    }
}
