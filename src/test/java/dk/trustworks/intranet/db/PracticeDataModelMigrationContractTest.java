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

    // ── Phase 5A (V428 — relax practice_lead.practice_code) ────────────────────

    @Test
    void v428_relaxes_the_practice_lead_code_column_so_5a_can_ship_alone() throws IOException {
        String m = code(readV428());
        // 5A's PracticeLead.practiceCode is an @Formula, which Hibernate never
        // emits in an INSERT; the column was NOT NULL with no default, so
        // POST /practices/{id}/leads 500s until this relaxation ships WITH 5A.
        assertTrue(m.contains("MODIFY COLUMN practice_code VARCHAR(10) NULL"),
                "practice_code must become nullable for the uuid-only writer");
        // NULL, never a fabricated legacy code, in a column that is about to die.
        assertFalse(m.contains("DEFAULT ''"), "an empty-string default would fabricate a fake legacy code");
        // V429 drops this column, so an out-of-order replay must no-op, not abort.
        assertTrue(m.contains("SET @practice_lead_has_code :="),
                "the ALTER must be guarded on the column still existing (V429 drops it)");
        assertTrue(m.contains("'DO 0'"), "the guard's no-op branch follows the house idiom");
        assertFalse(m.contains("DROP COLUMN"), "V428 only relaxes — the drop is V429's job");
    }

    // ── Phase 5B (V429 — legacy cleanup + canonical codes) ─────────────────────

    @Test
    void v429_recreates_the_consultant_view_before_dropping_the_column_it_reads() throws IOException {
        String m = code(readV429());
        assertTrue(m.contains("VIEW `consultant` AS"), "the consultant view must be recreated here");
        // Registry-derived, NOT the raw column — and NOT COALESCEd: this is a
        // user-shaped entity view, so no-practice stays NULL (spec §4.1).
        assertTrue(m.contains("prg.code AS practice"), "practice must derive from the registry join");
        assertTrue(m.contains("LEFT JOIN practice prg ON prg.uuid = u.practice_uuid"),
                "the derivation must resolve through the uuid twin");
        assertFalse(squash(m).contains("COALESCE(prg.code, 'UD')"),
                "no sentinel here — the flip removed it; only warehouse views synthesize the member token");
        // Asserted against the DDL only (comments stripped): the phrase also occurs
        // in the file's prose header, so a comment-inclusive check would pass even
        // if the clause were deleted and MariaDB silently defaulted to DEFINER.
        assertTrue(m.contains("SQL SECURITY INVOKER"), "the view's INVOKER security type must be explicit");
        // The Employee entity maps this view: the full 28-column contract, not just
        // the practice column, must survive the rewrite.
        for (String col : List.of(
                "u.uuid,", "u.created,", "u.email,", "u.firstname,", "u.lastname,", "u.gender,", "u.type,",
                "u.password,", "u.username,", "u.slackusername,", "u.birthday,", "u.cpr,", "u.phone,",
                "u.pension,", "u.healthcare,", "u.pensiondetails,", "u.defects,", "u.photoconsent,", "u.other,",
                "cl.career_track,", "cl.career_level,", "us.status,", "us.allocation,",
                "us.type AS consultanttype,", "COALESCE(s.salary, 0) AS salary,", "us.companyuuid")) {
            assertTrue(m.contains(col), "the consultant view must keep column " + col);
        }
        assertTrue(m.contains(") AS hiredate,"), "the hiredate scalar subquery must survive");
        // Ordering: the recreation must precede the drop of user.practice.
        assertTrue(m.indexOf("VIEW `consultant` AS") < m.indexOf("DROP COLUMN IF EXISTS practice;"),
                "the view must be recreated BEFORE user.practice is dropped, or it breaks at SELECT time");
    }

    @Test
    void v429_validates_data_before_it_destroys_anything() throws IOException {
        String m = code(readV429());
        // MariaDB DDL is non-transactional. The only two statements that can fail
        // on DATA are the strict-FK adds and the UD delete; both must precede the
        // six irreversible column drops so a data surprise aborts cleanly and
        // re-runs, instead of wedging the DB with the columns already gone.
        int firstDrop = m.indexOf("DROP COLUMN IF EXISTS");
        assertTrue(m.indexOf("ADD CONSTRAINT fk_user_practice FOREIGN KEY") < firstDrop,
                "the strict FKs must validate the data BEFORE any column is dropped");
        assertTrue(m.indexOf("DELETE FROM practice WHERE code = 'UD'") < firstDrop,
                "the UD delete must run BEFORE any column is dropped");
        assertTrue(m.indexOf("UPDATE fact_pipeline_snapshot fps") < firstDrop,
                "the snapshot release must run BEFORE any column is dropped");
    }

    @Test
    void v429_drops_the_code_foreign_keys_before_the_columns_they_constrain() throws IOException {
        String m = code(readV429());
        assertTrue(m.contains("DROP FOREIGN KEY IF EXISTS fk_team_practice"), "team's code FK must go");
        assertTrue(m.contains("DROP FOREIGN KEY IF EXISTS fk_practice_lead_practice"), "practice_lead's code FK must go");
        // Both reference practice(code) with RESTRICT — the fold fails while they exist.
        assertTrue(m.indexOf("DROP FOREIGN KEY IF EXISTS fk_team_practice") < m.indexOf("DROP COLUMN IF EXISTS practice_code;"),
                "FK drops must precede the column drops");
        assertTrue(m.indexOf("DROP FOREIGN KEY IF EXISTS fk_practice_lead_practice") < m.indexOf("SET @practice_fold :="),
                "both code FKs must be gone before the parent key is renamed");
        // The composite (practice_code, startdate) index is dropped explicitly —
        // dropping only the column would leave a degenerate index on (startdate).
        assertTrue(m.contains("DROP INDEX IF EXISTS idx_practice_lead_practice"),
                "the composite code index must be dropped explicitly, not left degenerate");
    }

    @Test
    void v429_drops_all_six_legacy_code_columns_from_the_right_tables() throws IOException {
        String m = code(readV429());
        // Table-targeted, not a blind count: a retargeted drop must not pass.
        for (String t : List.of("`user`", "user_practice_history", "sales_lead")) {
            assertTrue(m.contains("ALTER TABLE " + t + "\n    DROP COLUMN IF EXISTS practice;"),
                    t + " must lose its legacy practice column");
        }
        for (String t : List.of("team", "practice_lead")) {
            assertTrue(m.contains("ALTER TABLE " + t + "\n    DROP COLUMN IF EXISTS practice_code;"),
                    t + " must lose its legacy practice_code column");
        }
        assertTrue(m.contains("ALTER TABLE questionnaire\n    DROP COLUMN IF EXISTS target_practices;"),
                "the questionnaire code array is derived from the uuid array since Phase 5");
        // The trailing ';' matters: without it a typo'd column name prefix-matches
        // and IF EXISTS silently turns the drop into a no-op.
        int bareDrops = m.split("DROP COLUMN IF EXISTS practice;", -1).length - 1;
        assertTrue(bareDrops == 3, "exactly three tables lose practice (found " + bareDrops + ")");
    }

    @Test
    void v429_releases_the_snapshot_rows_before_deleting_the_ud_row() throws IOException {
        String m = code(readV429());
        assertTrue(m.contains("UPDATE fact_pipeline_snapshot fps"), "the frozen snapshot rows must be released");
        assertTrue(m.contains("JOIN practice p ON p.uuid = fps.practice_uuid AND p.code = 'UD'"),
                "the release must key on the UD row by code — uuids are environment-minted");
        assertTrue(m.indexOf("UPDATE fact_pipeline_snapshot fps") < m.indexOf("DELETE FROM practice"),
                "NULLing must precede the row delete — afterwards the join can no longer find it");
        assertFalse(squash(m).contains("ALTER TABLE fact_pipeline_snapshot ADD CONSTRAINT"),
                "frozen history gets no FK — its keys must survive registry evolution");
    }

    @Test
    void v429_deletes_the_ud_registry_row_but_keeps_the_member_token() throws IOException {
        String m = code(readV429());
        assertTrue(m.contains("DELETE FROM practice WHERE code = 'UD';"),
                "the UD registry row is dropped in this phase");
        // Keyed on code alone: depending on the type column would make the file
        // un-replayable once the V430 micro-step retires it.
        assertFalse(m.contains("DELETE FROM practice WHERE code = 'UD' AND type"),
                "the delete must not depend on the type column that V430 retires");
        // The literal token outlives the row: mat 'UD' buckets are never re-keyed.
        String flat = squash(m);
        assertFalse(flat.contains("WHERE practice_id = 'UD'") || flat.contains("WHERE service_line_id = 'UD'"),
                "'UD' mat buckets are the permanent member token — never re-keyed");
    }

    @Test
    void v429_renames_by_folding_display_code_on_the_legacy_codes_only() throws IOException {
        String m = code(readV429());
        // Keyed on the three legacy codes, NOT on "code <> display_code": the latter
        // is a rule rather than a fixed point and would re-fire on any later admin
        // edit that legitimately diverges the two columns.
        // Anchored on the closing quote of the SQL string literal, so an appended
        // predicate (e.g. AND 1 = 0) that neuters the fold cannot prefix-match.
        assertTrue(squash(m).contains("'UPDATE practice SET code = display_code WHERE code IN (''SA'', ''BA'', ''DEV'')',"),
                "the fold must key on exactly the three legacy codes so it is a true fixed point");
        assertFalse(m.contains("WHERE code <> display_code"),
                "a <>-keyed fold re-fires on later legitimate divergence — silently rewriting a live storage key");
        assertFalse(m.contains("UPDATE practice SET code = 'IA'"), "the rename must not hardcode target codes");
        // Guarded so the file stays replayable after V430 drops display_code.
        assertTrue(m.contains("SET @practice_has_display_code :="),
                "the fold must be guarded on display_code still existing");
    }

    @Test
    void v429_swaps_the_primary_key_to_uuid_and_keeps_code_unique() throws IOException {
        String m = code(readV429());
        assertTrue(m.contains("ALTER TABLE practice DROP PRIMARY KEY, ADD PRIMARY KEY (uuid), ADD UNIQUE KEY uk_practice_code (code)"),
                "the swap must be ONE statement so the table is never without a primary key");
        // No native IF NOT EXISTS for PRIMARY KEY — the swap is information_schema-guarded,
        // and the guard polarity matters: inverted, the swap never runs on a fresh DB.
        assertTrue(m.contains("IF(@practice_pk_is_uuid = 0,"), "the guard must fire when the PK is NOT yet uuid");
        // uuid must be the SOLE pk column, else a composite PK skips the swap and
        // then strands the file on the uq_practice_uuid drop below.
        assertTrue(m.contains("SEQ_IN_INDEX = 1"), "the guard must require uuid to be the sole primary key column");
        // Ordering anchored on EXECUTE, not on the literal inside the string
        // variable — the swap does not take effect until the statement runs.
        assertTrue(m.indexOf("EXECUTE practice_pk_stmt;") < m.indexOf("DROP INDEX IF EXISTS uq_practice_uuid"),
                "uq_practice_uuid may only be dropped once the PK actually backs the tpa foreign key");
    }

    @Test
    void v429_establishes_the_five_strict_foreign_keys_on_the_uuid_twins() throws IOException {
        String m = code(readV429());
        for (String fk : List.of(
                "fk_user_practice", "fk_user_practice_history_practice", "fk_sales_lead_practice",
                "fk_team_practice_uuid", "fk_practice_lead_practice_uuid")) {
            assertTrue(m.contains("ADD CONSTRAINT " + fk + " FOREIGN KEY IF NOT EXISTS (practice_uuid)"),
                    "must add " + fk + " on the uuid twin");
        }
        int restrict = m.split("REFERENCES practice \\(uuid\\) ON DELETE RESTRICT", -1).length - 1;
        assertTrue(restrict == 5, "all five new FKs are ON DELETE RESTRICT (found " + restrict + ")");
        assertFalse(m.contains("ON DELETE SET NULL") || m.contains("ON DELETE CASCADE"),
                "no weakening of the delete rule — RESTRICT is the house default");
        // The three previously-unindexed twins get house-named indexes rather than
        // letting InnoDB auto-name them after the constraint.
        for (String idx : List.of(
                "idx_user_practice_uuid", "idx_user_practice_history_practice_uuid", "idx_sales_lead_practice_uuid")) {
            assertTrue(m.contains("ADD KEY IF NOT EXISTS " + idx + " (practice_uuid)"), "must add " + idx);
        }
    }

    @Test
    void v429_rekeys_the_four_mat_tables_in_place_with_the_right_mapping() throws IOException {
        String m = squash(code(readV429()));
        // Assert the full SET->WHERE mapping per table, not just the WHERE clause:
        // the point of this section is SA->IA / BA->BU / DEV->TECH specifically.
        for (String mat : List.of("fact_employee_monthly_mat", "fact_opex_mat")) {
            assertTrue(m.contains("UPDATE " + mat + " SET practice_id = 'IA' WHERE practice_id = 'SA';"), mat + " SA to IA");
            assertTrue(m.contains("UPDATE " + mat + " SET practice_id = 'BU' WHERE practice_id = 'BA';"), mat + " BA to BU");
            assertTrue(m.contains("UPDATE " + mat + " SET practice_id = 'TECH' WHERE practice_id = 'DEV';"), mat + " DEV to TECH");
        }
        for (String mat : List.of("fact_project_financials_mat", "fact_revenue_budget_mat")) {
            assertTrue(m.contains("UPDATE " + mat + " SET service_line_id = 'IA' WHERE service_line_id = 'SA';"), mat + " SA to IA");
            assertTrue(m.contains("UPDATE " + mat + " SET service_line_id = 'BU' WHERE service_line_id = 'BA';"), mat + " BA to BU");
            assertTrue(m.contains("UPDATE " + mat + " SET service_line_id = 'TECH' WHERE service_line_id = 'DEV';"), mat + " DEV to TECH");
        }
    }

    @Test
    void v429_retains_display_code_and_type_for_the_draining_canary_task() throws IOException {
        String m = squash(code(readV429()));
        // The 5A task still maps display_code and filters on type='PRACTICE'.
        // Dropping either here would break it mid-cutover; both are a trailing
        // micro-step (V430) once 5A has drained. Deviation recorded in spec §1.6.K.
        // Backtick-tolerant: type is quasi-reserved and the file already backticks user.
        for (String col : List.of("display_code", "`display_code`", "type", "`type`")) {
            assertFalse(m.contains("DROP COLUMN IF EXISTS " + col + ";") || m.contains("DROP COLUMN " + col + ";"),
                    col + " must survive V429 — the draining 5A task reads it");
        }
        assertFalse(m.contains("DROP TABLE "), "V429 drops columns and one row, never a table");
    }

    // ── Teams admin page (V431 — the settings-teams tab registration) ─────────

    @Test
    void v431_registers_the_teams_settings_tab_with_its_own_icon() throws IOException {
        String m = code(readV431());
        // Both halves of the registration are load-bearing: without the row the
        // tab never renders, whatever the frontend does.
        assertTrue(m.contains("'settings-teams'"), "page_registry key settings-teams");
        assertTrue(m.contains("'/settings?tab=teams'"), "react_route must address the sibling tab");
        assertTrue(m.contains("'ADMIN'"), "the tab is ADMIN-gated");
        assertTrue(m.contains("'SETTINGS'"), "the row belongs to the SETTINGS section");
        assertTrue(m.contains("140"), "display_order 140 places it after settings-practices (130)");
        // 'Users' is already the icon of two other pages — a third would be
        // ambiguous, so this tab gets UsersRound.
        assertTrue(m.contains("'UsersRound'"), "icon must be UsersRound, not the twice-used Users");
        assertFalse(m.contains("'Users'"), "the ambiguous Users icon must not be reused here");
    }

    @Test
    void v431_is_an_idempotent_data_only_upsert() throws IOException {
        String m = code(readV431());
        assertTrue(m.contains("INSERT INTO page_registry"), "registration mirrors the V418 seed");
        assertTrue(m.contains("ON DUPLICATE KEY UPDATE"), "re-runs must converge, not collide");
        String upper = m.toUpperCase();
        assertFalse(upper.contains("CREATE TABLE") || upper.contains("ALTER TABLE")
                        || upper.contains("DROP TABLE") || upper.contains("DROP COLUMN"),
                "V431 registers a page — it carries no schema change");
    }

    /** Strips SQL line comments so assertions cannot be satisfied by prose in a header. */
    private static String code(String migration) {
        return migration.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** Collapses runs of whitespace so assertions are not coupled to column alignment. */
    private static String squash(String sql) {
        return sql.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", " ");
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

    private static String readV428() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V428__Practice_phase5a_relax_practice_lead_code.sql"));
    }

    private static String readV429() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V429__Practice_phase5_cleanup_canonical_codes.sql"));
    }

    private static String readV431() throws IOException {
        return Files.readString(MIGRATIONS.resolve("V431__Add_teams_admin_page_registry.sql"));
    }
}
