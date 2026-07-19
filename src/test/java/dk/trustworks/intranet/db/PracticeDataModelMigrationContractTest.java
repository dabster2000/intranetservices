package dk.trustworks.intranet.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for V418 — the practice registry + team-settings migration.
 * Reads the SQL file and asserts the structural facts the frontend and the
 * runtime code depend on (tables, collation, FKs, seeds, display renames,
 * budget seeds, the user.practice default and the settings page registration),
 * plus that the migration is additive (no DROP).
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

    private static String read() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V418__Create_practice_registry_and_team_settings.sql"));
    }
}
