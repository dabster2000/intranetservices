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
        // Additive only — check for structural DROP DDL (mirrors migration_is_additive_only),
        // not the bare word "DROP" which also appears in the rollback comment.
        String upper = migration.toUpperCase();
        assertFalse(upper.contains("DROP TABLE") || upper.contains("DROP COLUMN") || upper.contains("DROP INDEX"),
                "V421 is additive only");
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

    // ── Phase 1 migrations (V424 dual-key foundation, V425 new structures) ───

    @Test
    void v424_adds_practice_uuid_unique_and_populated() throws IOException {
        String m = readV424();
        assertTrue(m.contains("ADD COLUMN IF NOT EXISTS uuid VARCHAR(36)"), "practice.uuid added idempotently");
        assertTrue(m.contains("SET uuid = UUID() WHERE uuid IS NULL"), "uuid populated only where NULL (converges on re-run)");
        assertTrue(m.contains("MODIFY COLUMN uuid VARCHAR(36) NOT NULL"), "uuid becomes NOT NULL");
        assertTrue(m.contains("uq_practice_uuid"), "uuid is UNIQUE (code stays the PK)");
    }

    @Test
    void v424_adds_dual_practice_uuid_columns_on_the_five_durable_tables() throws IOException {
        String m = readV424();
        // A practice_uuid twin on each of the five durable tables.
        int twinAdds = m.split("ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR\\(36\\)", -1).length - 1;
        assertTrue(twinAdds >= 5, "practice_uuid twin on user/uph/team/practice_lead/sales_lead (found " + twinAdds + ")");
        // Every backfill derives the uuid from the code column via a registry join
        // (converges under uuid re-mint) and is keyed on IS NULL (idempotent).
        assertTrue(m.contains("JOIN practice p ON p.code = u.practice"), "user backfill joins code→uuid");
        assertTrue(m.contains("WHERE u.practice_uuid IS NULL"), "user backfill keyed on IS NULL");
        assertTrue(m.contains("idx_team_practice_uuid"), "team twin indexed (code column is indexed)");
        assertTrue(m.contains("idx_practice_lead_practice_uuid"), "practice_lead twin indexed (code column is indexed)");
    }

    @Test
    void v424_adds_the_provenance_columns() throws IOException {
        String m = readV424();
        assertTrue(m.contains("source_team_uuid VARCHAR(36) NULL"), "uph gains source_team_uuid");
        assertTrue(m.contains("updated_by       VARCHAR(36) NULL")
                || m.contains("updated_by VARCHAR(36) NULL"), "uph gains updated_by");
    }

    @Test
    void v424_recreates_the_trigger_family_mirroring_both_keys() throws IOException {
        String m = readV424();
        // Two new BEFORE triggers mirror practice_uuid onto the row (NULL-safe).
        assertTrue(m.contains("trg_user_practice_uuid_before_insert"), "BEFORE INSERT mirror trigger");
        assertTrue(m.contains("trg_user_practice_uuid_before_update"), "BEFORE UPDATE mirror trigger");
        assertTrue(m.contains("SET NEW.practice_uuid = (SELECT p.uuid FROM practice p WHERE p.code = NEW.practice)"),
                "mirror derives uuid from the code (NULL-safe)");
        // The three AFTER triggers are recreated and carry practice_uuid onto history.
        assertTrue(m.contains("trg_user_practice_history_after_insert"), "AFTER INSERT recreated");
        assertTrue(m.contains("trg_user_practice_history_after_update"), "AFTER UPDATE recreated");
        assertTrue(m.contains("trg_user_practice_history_after_delete"), "AFTER DELETE recreated");
        assertTrue(m.contains("OLD.practice <=> NEW.practice"), "change-detection preserved (code column)");
        int dropTriggers = m.split("DROP TRIGGER IF EXISTS", -1).length - 1;
        assertTrue(dropTriggers >= 5, "every trigger DROP IF EXISTS + CREATE for idempotency (found " + dropTriggers + ")");
    }

    @Test
    void v425_creates_team_practice_assignment_temporal_and_seeded() throws IOException {
        String m = readV425();
        assertTrue(m.contains("CREATE TABLE IF NOT EXISTS team_practice_assignment"), "temporal assignment table");
        assertTrue(m.contains("fk_tpa_team"), "FK team_uuid → team(uuid)");
        assertTrue(m.contains("fk_tpa_practice"), "FK practice_uuid → practice(uuid)");
        assertTrue(m.contains("REFERENCES practice (uuid)"), "FK targets the surrogate key");
        // House audit columns (Phase 0 Auditable pattern).
        assertTrue(m.contains("created_by") && m.contains("modified_by"), "house audit columns");
        // Seed: one open row per practice-assigned team, startdate = the V418 backfill date.
        assertTrue(m.contains("'2026-07-19'"), "seed startdate = the V418 backfill date");
        assertTrue(m.contains("WHERE t.practice_code IS NOT NULL"), "seed only practice-assigned teams");
        assertTrue(m.contains("NOT EXISTS (SELECT 1 FROM team_practice_assignment"), "seed idempotent (no duplicate rows)");
    }

    @Test
    void v425_dual_keys_questionnaire_without_rewriting_the_code_array() throws IOException {
        String m = readV425();
        assertTrue(m.contains("ADD COLUMN IF NOT EXISTS target_practice_uuids"), "adds the uuid twin column");
        assertTrue(m.contains("JSON_TABLE"), "backfill maps codes→uuids via JSON, preserving order");
        // The code array stays authoritative — it must NOT be rewritten this phase.
        assertFalse(m.contains("SET q.target_practices ="), "target_practices (codes) must stay authoritative");
        assertFalse(m.contains("SET target_practices ="), "target_practices (codes) must stay authoritative");
    }

    @Test
    void v425_rekeys_fact_pipeline_snapshot_and_drops_the_dead_code_column() throws IOException {
        String m = readV425();
        assertTrue(m.contains("ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice"),
                "fps gains practice_uuid");
        assertTrue(m.contains("CREATE OR REPLACE PROCEDURE sp_snapshot_pipeline"), "snapshot proc recreated to write uuid");
        assertTrue(m.contains("CREATE OR REPLACE PROCEDURE sp_backfill_pipeline_snapshots"), "backfill proc recreated");
        assertTrue(m.contains("preg.uuid AS practice_uuid"), "procs resolve sl.practice→registry uuid at snapshot time");
        // The dead code column + its index are dropped (Hans's clean-solution decision).
        assertTrue(m.contains("DROP COLUMN IF EXISTS practice"), "dead practice column dropped");
        assertTrue(m.contains("DROP INDEX IF EXISTS idx_fps_month_practice"), "old practice index dropped");
        // The backfill is guarded so it no-ops once the column is gone (repair re-run).
        assertTrue(m.contains("information_schema.COLUMNS") && m.contains("COLUMN_NAME = 'practice'"),
                "fps backfill guarded by column-existence check");
    }

    @Test
    void v425_drops_practice_settings_and_adds_the_lead_user_fk() throws IOException {
        String m = readV425();
        assertTrue(m.contains("DROP TABLE IF EXISTS practice_settings"), "empty practice_settings table dropped");
        assertTrue(m.contains("fk_practice_lead_user"), "practice_lead.useruuid FK → user(uuid)");
        assertTrue(m.contains("REFERENCES `user` (uuid)"), "FK targets user(uuid)");
    }

    // ── Phase 2 migration (V426 — application becomes the sole writer) ───────

    @Test
    void v426_drops_all_five_triggers_idempotently() throws IOException {
        String m = readV426();
        // The exact family recreated by V424 — the two uuid mirrors and the
        // three history writers — must all go, each with IF EXISTS.
        for (String trigger : List.of(
                "trg_user_practice_uuid_before_insert",
                "trg_user_practice_uuid_before_update",
                "trg_user_practice_history_after_insert",
                "trg_user_practice_history_after_update",
                "trg_user_practice_history_after_delete")) {
            assertTrue(m.contains("DROP TRIGGER IF EXISTS " + trigger), "must drop " + trigger);
        }
        int drops = m.split("DROP TRIGGER IF EXISTS", -1).length - 1;
        assertTrue(drops >= 5, "all five triggers dropped (found " + drops + ")");
        assertFalse(m.toUpperCase().contains("CREATE TRIGGER"),
                "V426 must not recreate any trigger — the application is the sole writer");
    }

    @Test
    void v426_resyncs_the_twin_columns_from_the_code_columns() throws IOException {
        String m = readV426();
        // Convergence resyncs derive the uuid from the code via a registry join
        // and are keyed on NULL-safe mismatch, so re-runs are no-ops.
        assertTrue(m.contains("SET u.practice_uuid = p.uuid"), "user twin resynced");
        assertTrue(m.contains("SET t.practice_uuid = p.uuid"), "team twin resynced");
        assertTrue(m.contains("SET h.practice_uuid = p.uuid"), "history twin resynced");
        int nullSafeGuards = m.split("<=>", -1).length - 1;
        assertTrue(nullSafeGuards >= 3, "every resync keyed on NULL-safe mismatch (found " + nullSafeGuards + ")");
    }

    @Test
    void v426_documents_that_raw_sql_writes_no_history() throws IOException {
        String m = readV426();
        assertTrue(m.contains("BY DESIGN") && m.contains("writes NO"),
                "header must state that raw SQL practice updates write no history (tick reconciles)");
        // Trigger drops only — no structural DDL rides along.
        String upper = m.toUpperCase();
        assertFalse(upper.contains("DROP TABLE") || upper.contains("DROP COLUMN") || upper.contains("DROP INDEX"),
                "V426 drops triggers only");
    }

    // ── Phase 4 migration (V427 — warehouse recreation + operational NULL flip) ─

    @Test
    void v427_makes_the_history_practice_column_nullable_and_drops_the_ud_default() throws IOException {
        String m = readV427();
        assertTrue(m.contains("MODIFY COLUMN practice VARCHAR(50) NULL"),
                "user_practice_history.practice must become nullable (NULL periods are first-class history)");
        assertTrue(m.contains("MODIFY COLUMN practice VARCHAR(3) NULL DEFAULT NULL"),
                "user.practice loses the V418 'UD' default — NULL is the no-practice default now");
    }

    @Test
    void v427_flips_ud_to_null_on_all_three_operational_tables_both_twins() throws IOException {
        String m = readV427();
        // One flip per table, setting BOTH key twins, keyed on the stored code
        // OR a drifted UD-registry uuid (defensive), so re-runs match zero rows.
        for (String table : List.of("UPDATE `user` u", "UPDATE user_practice_history h", "UPDATE sales_lead sl")) {
            assertTrue(m.contains(table), "flip must cover " + table);
        }
        int nullPairs = m.split("practice = NULL,\n    \\w+\\.practice_uuid = NULL", -1).length - 1;
        assertTrue(nullPairs >= 3, "each flip must NULL both twins together (found " + nullPairs + ")");
        int udJoins = m.split("LEFT JOIN practice ud ON ud.code = 'UD' AND ud.uuid =", -1).length - 1;
        assertTrue(udJoins >= 3, "each flip must also catch uuid-only drift rows (found " + udJoins + ")");
        assertFalse(m.contains("UPDATE team"), "team is not a flip target — teams never stored 'UD'");
        assertFalse(m.contains("DELETE FROM practice"), "the UD registry row survives until Phase 5");
    }

    @Test
    void v427_recreates_the_seven_practice_dimensioned_views_with_the_member_mapping() throws IOException {
        String m = readV427();
        for (String view : List.of(
                "fact_backlog", "fact_employee_monthly", "fact_pipeline", "fact_project_financials",
                "fact_revenue_budget", "fact_salary_monthly", "fact_staffing_forecast_week")) {
            assertTrue(m.contains("VIEW `" + view + "` AS") || m.contains("VIEW " + view + " AS"),
                    "must recreate " + view);
        }
        // The synthetic member: registry LEFT JOIN + COALESCE(code,'UD') and the
        // 'No practice' label — never a NULL group key, survives the Phase 5 row drop.
        int memberMappings = m.split("COALESCE\\(\\w+\\.code, 'UD'\\)", -1).length - 1;
        assertTrue(memberMappings >= 6, "views must emit COALESCE(<registry>.code,'UD') (found " + memberMappings + ")");
        int labels = m.split("'No practice'", -1).length - 1;
        assertTrue(labels >= 7, "each practice-dimensioned view carries the additive practice_label (found " + labels + ")");
        assertFalse(m.contains("VIEW `consultant`") || m.contains("VIEW consultant AS"),
                "the user-shaped consultant view is deliberately untouched — its practice column follows operational NULL");
    }

    @Test
    void v427_pins_the_dominant_vote_tie_break_and_keeps_no_practice_voters() throws IOException {
        String m = readV427();
        // The two dominant-practice votes must not exclude no-practice consultants
        // (the old `u.practice IS NOT NULL` filter would silently drop the whole
        // former-UD population from the vote after the flip)...
        assertFalse(m.contains("u.practice IS NOT NULL"),
                "no vote may filter no-practice voters out — they participate as the 'UD' member");
        // ...and ties resolve deterministically to registry sort_order (documented
        // one-time shuffle of previously plan-arbitrary tied winners, spec §1.6.J).
        int tieBreaks = m.split("MIN\\(COALESCE\\(vprg.sort_order, 2147483647\\)\\) ASC", -1).length - 1;
        assertTrue(tieBreaks >= 2, "both votes pin the sort_order tie-break (found " + tieBreaks + ")");
    }

    @Test
    void v427_repoints_the_snapshot_procs_to_the_uuid_twin() throws IOException {
        String m = readV427();
        assertTrue(m.contains("CREATE OR REPLACE PROCEDURE sp_snapshot_pipeline"), "snapshot proc recreated");
        assertTrue(m.contains("CREATE OR REPLACE PROCEDURE sp_backfill_pipeline_snapshots"), "backfill proc recreated");
        int uuidJoins = m.split("LEFT JOIN practice preg ON preg.uuid = sl.practice_uuid", -1).length - 1;
        assertTrue(uuidJoins >= 2, "both procs resolve via sl.practice_uuid — sl.practice is dropped in Phase 5");
        assertFalse(m.contains("preg.code = sl.practice"),
                "no proc may keep joining the legacy code column");
    }

    @Test
    void v427_touches_no_mat_table_and_drops_nothing() throws IOException {
        String m = readV427();
        String upper = m.toUpperCase();
        assertFalse(upper.contains("ALTER TABLE FACT_"),
                "mat tables need no change — code columns are already VARCHAR(50) everywhere");
        assertFalse(upper.contains("DROP TABLE") || upper.contains("DROP COLUMN") || upper.contains("DROP VIEW"),
                "V427 recreates and flips; destructive drops are Phase 5");
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

    private static String readV424() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V424__Practice_phase1_uuid_dual_key.sql"));
    }

    private static String readV425() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V425__Practice_phase1_new_structures.sql"));
    }

    private static String readV426() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V426__Practice_phase2_drop_triggers_application_writer.sql"));
    }

    private static String readV427() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V427__Practice_phase4_operational_null_flip_and_warehouse.sql"));
    }
}
