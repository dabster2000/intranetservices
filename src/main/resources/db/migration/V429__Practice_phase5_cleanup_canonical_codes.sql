-- =============================================================================
-- V429: Practice Part 2, Phase 5B — legacy cleanup + canonical codes.
--
-- The destructive endgame of the Part 2 rollout. Every legacy practice CODE
-- column disappears from the operational schema, the registry becomes
-- uuid-keyed, and the storage codes finally become the canonical ones Hans
-- chose in Part 1 (spec §4.4 Q3): SA→IA, BA→BU, DEV→TECH (PM/CYB unchanged).
--
-- This is the 5B half of a deliberate TWO-RELEASE split (spec §1.6.K). 5A —
-- the code-only re-key on this file's PARENT BRANCH, together with V428's
-- practice_lead relaxation — removed every read and every write of a legacy
-- code column: the entity fields became registry-derived @Formula subqueries.
-- 5A was verified byte-identical against a 32-endpoint golden master, ships
-- first, and bakes. This migration-only release lands afterwards.
--
--   ⚠ V429 MUST NEVER SHIP WITHOUT 5A ALREADY DEPLOYED. On any pre-5A code
--     (origin/master, origin/staging) the entities still map the columns §5
--     drops, so every User/Team/SalesLead load would 500 with ER_BAD_FIELD.
--     The stacked-PR structure enforces this: 5B is based on the 5A branch.
--
-- The consequence that drives most of the ordering below: during the canary
-- window the DRAINING 5A task is still serving traffic against this schema.
--
-- ── What the draining 5A task still needs (the canary contract) ──────────────
--   * `practice.display_code` — the Practice entity maps the column and serves
--     displayCode on GET /practices. RETAINED here: this file only CONVERGES
--     its value (code := display_code) so the two agree on every row. The
--     physical drop plus the entity change (getDisplayCode() ≡ code) is a
--     documented TRAILING MICRO-STEP for a later release (V430). Deviation
--     from §4.1's "fold and retire" — recorded in §1.6.K and both PR bodies.
--   * `practice.type` — activePracticeUserFilter/activePracticeCodeFilter
--     filter on type='PRACTICE'. RETAINED for the same reason; vestigial once
--     the UD row (the only SEGMENT) is gone. Same trailing micro-step.
--     NOTE: no statement below DEPENDS on either column existing except §6's
--     fold, which is guarded — so this file stays replayable after V430.
--   * the `consultant` view's `practice` column — the Employee entity maps it.
--     Recreated in §1 BEFORE user.practice is dropped, so the column never
--     stops resolving. Without this the view would survive syntactically and
--     fail only at SELECT time with ER_VIEW_INVALID.
--
-- ── Ordering rule: VALIDATE BEFORE DESTROYING ───────────────────────────────
-- MariaDB DDL is non-transactional and this file is not wrapped in one, so a
-- mid-file abort leaves partial state. Only two statements can fail on DATA:
-- the strict-FK adds (orphan practice_uuid) and the UD row delete (a surviving
-- reference). BOTH are ordered AHEAD of the six irreversible DROP COLUMNs, so
-- a data surprise on an environment whose history differs from staging aborts
-- CLEANLY and re-runs, instead of wedging the DB with the columns already gone
-- and every replay dying at the same statement. Sections 2→4 are all
-- non-destructive; §5 is the point of no return.
--
-- ── The 'UD' member token graduates ─────────────────────────────────────────
-- The UD registry ROW is deleted here (§4). The literal 'UD' STRING does not
-- die with it — it is now a permanent, registry-independent member token:
--   * the warehouse views synthesize it via COALESCE(prg.code,'UD') (V427 §2),
--     which keeps working precisely because it never referenced the UD row;
--   * the 5A resolveFilterToken has a literal 'UD' short-circuit and
--     orderedRegistryCodes() appends 'UD' when absent — both no-ops until this
--     migration, load-bearing immediately after it (career matrix, pay equity);
--   * the 4 mat tables keep their 'UD' buckets untouched in §9.
--
-- ── Why the mat tables are updated in place (the 24h-gap trap) ───────────────
-- The BI mat tables are TRUNCATE+INSERT..SELECT refreshed from the warehouse
-- views, and every one of those paths resolves the code through the registry
-- off practice_uuid (three do it via a CTE column that is itself
-- COALESCE(vprg.code,'UD') — a self-consistent round-trip, not a raw code
-- read). No view or routine hardcodes a legacy code. So once §6 folds
-- practice.code, the next refresh emits canonical codes on its own — the fold
-- IS the durability mechanism and no view needs recreating for the rename.
-- But the mats hold LEGACY codes until that refresh, and 5A's
-- activePracticeCodeFilter would mismatch them for up to ~24h. §9 closes that
-- window; the nightly refresh then re-derives exactly the same values.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §4.1 (target model), §4.4 wave 4 (canonical codes), §1.6.K.
-- Plan:  docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md
--        Phase 5.
--
-- Verified state at authoring time (local twservices4-staging @ V427):
--   * zero orphans — every non-NULL practice_uuid on user / user_practice_history
--     / sales_lead / team / practice_lead / team_practice_assignment resolves to
--     a registry row, so the strict FKs in §3 validate without repair;
--   * zero drift between every legacy code column and its uuid twin (user 238,
--     sales_lead 1068, user_practice_history 253, team 15, practice_lead 0), so
--     the §5 drops destroy no information;
--   * practice_lead is EMPTY — its column drop carries no data risk;
--   * exactly 21 of 2021 fact_pipeline_snapshot rows hold the UD uuid (§2), and
--     a scan of all 1258 uuid-width columns found no other holder;
--   * `consultant` is the ONLY database object referencing any dropped column
--     (all views enumerated; no proc, trigger, generated column or CHECK
--     constraint references one).
--
-- Idempotency (mandatory — this file re-runs via repair-at-start and after the
-- nightly prod→staging refresh strips it): the view is CREATE OR REPLACE; every
-- drop uses a native IF EXISTS shorthand; every ADD uses IF NOT EXISTS; the
-- snapshot NULLing and the UD delete key on the UD row itself, so both match
-- zero rows once it is gone; the fold keys on the three LEGACY codes (never on
-- "code <> display_code", which is a rule rather than a fixed point and would
-- re-fire on any later admin edit that legitimately diverges the two); the PK
-- swap and the fold are information_schema-guarded.
--
-- ECS canary note: during cutover the draining 5A task keeps serving against
-- this schema. It reads practice codes exclusively through registry joins, so
-- it observes the rename as data, not as breakage — and the three things it
-- still needs (display_code, type, consultant.practice) are all preserved
-- above. The incoming 5B task is identical code; this is a migration-only
-- release precisely so no code change races the schema change.
--
-- Rollback: forward-only per repo discipline. The dropped columns are exact
-- duplicates of surviving uuid twins (zero drift verified above), so the
-- pre-drop state is reconstructable by joining the registry; the code rename is
-- reversible via display_code until the V430 micro-step retires it; the
-- consultant view is recreatable from V226.
-- =============================================================================


-- =============================================================================
-- 1) Recreate the `consultant` view BEFORE user.practice is dropped.
--    Source of record: V226__Fix_consultant_view_duplicates.sql (verified the
--    latest — no migration V227–V428 touches this view). All 28 columns, their
--    order, and the three ROW_NUMBER de-duplication subqueries are reproduced
--    byte-identically; the ONLY change is `u.practice` → a registry join on
--    the uuid twin.
--
--    NO COALESCE here. This is a user-shaped entity view, not a warehouse
--    dimension: no-practice must stay NULL, exactly as the Phase 4 flip made it
--    on the underlying table (spec §4.1; the Phase 5 prompt's COALESCE idiom is
--    superseded — it would reintroduce the sentinel the flip just removed).
--    No consumer filters, groups or sorts on this column; all four read paths
--    (Employee entity, EmployeeResource, PublicResource, NewsService SELECT *)
--    treat it as an opaque nullable string.
--
--    The derivation also widens the column varchar(3) → varchar(10), which is
--    REQUIRED: 'TECH' is 4 characters and would have truncated to 'TEC' had the
--    view kept reading user.practice.
--
--    SQL SECURITY INVOKER is written EXPLICITLY: the live view carries it, but
--    V226's text omits the clause and MariaDB's default is DEFINER — a bare
--    CREATE OR REPLACE would silently change the security model.
--
--    CREATE OR REPLACE (not V226's DROP + CREATE): atomic, so the view is never
--    briefly absent to the draining canary task.
-- =============================================================================

CREATE OR REPLACE
    ALGORITHM = UNDEFINED
    SQL SECURITY INVOKER
VIEW `consultant` AS
SELECT
    u.uuid,
    u.created,
    u.email,
    u.firstname,
    u.lastname,
    u.gender,
    u.type,
    u.password,
    u.username,
    u.slackusername,
    u.birthday,
    u.cpr,
    u.phone,
    u.pension,
    u.healthcare,
    u.pensiondetails,
    u.defects,
    u.photoconsent,
    u.other,
    -- Registry-derived practice code (Phase 5): follows code renames with no
    -- view change, NULL for the no-practice population.
    prg.code AS practice,
    cl.career_track,
    cl.career_level,
    us.status,
    us.allocation,
    us.type AS consultanttype,
    COALESCE(s.salary, 0) AS salary,
    (
        SELECT MIN(us2.statusdate)
        FROM userstatus us2
        WHERE us2.useruuid = u.uuid
          AND us2.status = 'ACTIVE'
    ) AS hiredate,
    us.companyuuid
FROM user u
LEFT JOIN practice prg ON prg.uuid = u.practice_uuid
LEFT JOIN (
    SELECT us1.*, ROW_NUMBER() OVER (PARTITION BY us1.useruuid ORDER BY us1.statusdate DESC, us1.uuid DESC) AS rn
    FROM userstatus us1
    WHERE us1.statusdate <= CURDATE()
) us ON u.uuid = us.useruuid AND us.rn = 1
LEFT JOIN (
    SELECT s1.*, ROW_NUMBER() OVER (PARTITION BY s1.useruuid ORDER BY s1.activefrom DESC, s1.uuid DESC) AS rn
    FROM salary s1
) s ON u.uuid = s.useruuid AND s.rn = 1
LEFT JOIN (
    SELECT cl1.*, ROW_NUMBER() OVER (PARTITION BY cl1.useruuid ORDER BY cl1.active_from DESC, cl1.uuid DESC) AS rn
    FROM user_career_level cl1
    WHERE cl1.active_from <= CURDATE()
) cl ON u.uuid = cl.useruuid AND cl.rn = 1;


-- =============================================================================
-- 2) Release the last stored references to the UD registry row.
--    fact_pipeline_snapshot is append-only history with no FK on practice_uuid
--    and no reader that resolves it; 21 of 2021 rows froze the UD uuid before
--    the Phase 4 flip. Deleting the row in §4 would leave them dangling, so
--    they are NULLed first — the same treatment V425 gave the JK stragglers,
--    and the resolution Hans confirmed over §1.6.J's literal "untouched"
--    (recorded in §1.6.K). Downstream this is invisible: fact_pipeline maps the
--    rows through COALESCE(prg.code,'UD'), which yields 'UD' either way.
--
--    The join keys on the UD row by CODE, never on a hard-coded uuid (uuids are
--    environment-minted), so this works on every environment; and on a re-run
--    after §4 the join finds nothing and the statement matches 0 rows.
-- =============================================================================

UPDATE fact_pipeline_snapshot fps
    JOIN practice p ON p.uuid = fps.practice_uuid AND p.code = 'UD'
SET fps.practice_uuid = NULL;


-- =============================================================================
-- 3) Strict FKs on the uuid twins — the invariant the code columns used to
--    carry, now enforced where the data actually lives.
--
--    DELIBERATELY ORDERED BEFORE THE DROPS (see the header's validate-before-
--    destroying rule): these constraints VALIDATE existing data, so on an
--    environment carrying an orphan practice_uuid this aborts while the legacy
--    columns are still intact and the migration can simply be re-run after the
--    orphan is repaired. Placed after the drops, the same orphan would destroy
--    the columns first and then fail forever on every replay.
--
--    ON DELETE RESTRICT (house default, matches fk_tpa_practice) and every
--    column stays NULL-permitting: NULL is "no practice", a first-class value
--    since Phase 4, and FKs ignore NULLs. There is no DELETE path on the
--    practice registry (PracticeResource exposes GET/POST/PUT only;
--    deactivation is an UPDATE of `active`), so RESTRICT is pure hardening
--    rather than a new 500 surface.
--
--    user / user_practice_history / sales_lead have no index on practice_uuid;
--    the explicit ADD KEY keeps the index name under house convention instead
--    of letting InnoDB auto-name it after the constraint. team and
--    practice_lead already carry theirs from V424.
-- =============================================================================

ALTER TABLE `user`
    ADD KEY IF NOT EXISTS idx_user_practice_uuid (practice_uuid);

ALTER TABLE user_practice_history
    ADD KEY IF NOT EXISTS idx_user_practice_history_practice_uuid (practice_uuid);

ALTER TABLE sales_lead
    ADD KEY IF NOT EXISTS idx_sales_lead_practice_uuid (practice_uuid);

ALTER TABLE `user`
    ADD CONSTRAINT fk_user_practice FOREIGN KEY IF NOT EXISTS (practice_uuid) REFERENCES practice (uuid) ON DELETE RESTRICT;

ALTER TABLE user_practice_history
    ADD CONSTRAINT fk_user_practice_history_practice FOREIGN KEY IF NOT EXISTS (practice_uuid) REFERENCES practice (uuid) ON DELETE RESTRICT;

ALTER TABLE sales_lead
    ADD CONSTRAINT fk_sales_lead_practice FOREIGN KEY IF NOT EXISTS (practice_uuid) REFERENCES practice (uuid) ON DELETE RESTRICT;

ALTER TABLE team
    ADD CONSTRAINT fk_team_practice_uuid FOREIGN KEY IF NOT EXISTS (practice_uuid) REFERENCES practice (uuid) ON DELETE RESTRICT;

ALTER TABLE practice_lead
    ADD CONSTRAINT fk_practice_lead_practice_uuid FOREIGN KEY IF NOT EXISTS (practice_uuid) REFERENCES practice (uuid) ON DELETE RESTRICT;

--    fact_pipeline_snapshot deliberately gets NO FK: it is frozen history whose
--    keys must survive registry evolution (that is exactly why §2 had to NULL
--    rather than rely on a constraint).


-- =============================================================================
-- 4) Delete the UD registry row.
--    Still non-destructive to the schema, and now guarded by §3's RESTRICT FKs:
--    if any environment holds a reference this phase did not anticipate, this
--    DELETE fails cleanly (ER_ROW_IS_REFERENCED) with the legacy columns still
--    intact, rather than after they are gone.
--
--    Verified zero remaining references at authoring time: the five twin
--    columns and team_practice_assignment all hold 0 rows pointing at it, the
--    questionnaire uuid array does not contain it, and §2 has just released the
--    snapshot rows. 'UD' survives as a synthesized member token (header).
--
--    Keyed on `code` alone — `code` is unique, and NOT depending on the `type`
--    column keeps this statement replayable after the V430 micro-step retires
--    that column.
-- =============================================================================

DELETE FROM practice WHERE code = 'UD';


-- =============================================================================
-- 5) POINT OF NO RETURN — drop the legacy code columns and the FKs/indexes that
--    hang off them. Everything above this line is reversible or non-destructive
--    and every data-dependent check has already passed.
--
--    The two code FKs MUST die before §6 folds practice.code — they are
--    ON UPDATE RESTRICT against practice(code), so renaming the parent key
--    while they exist would fail.
-- =============================================================================

-- 5a) team.practice_code — FK + its backing KEY, then the column. -------------
ALTER TABLE team
    DROP FOREIGN KEY IF EXISTS fk_team_practice;

ALTER TABLE team
    DROP INDEX IF EXISTS fk_team_practice;

ALTER TABLE team
    DROP COLUMN IF EXISTS practice_code;

-- 5b) practice_lead.practice_code (table is empty — structural only). ---------
--     Made nullable by V428 so the 5A writer could run; now it goes entirely.
--     idx_practice_lead_practice is (practice_code, startdate): dropped
--     EXPLICITLY, because dropping only the column would leave a degenerate
--     single-column index on (startdate). The uuid equivalent
--     idx_practice_lead_practice_uuid (practice_uuid, startdate) already exists
--     from V424 and carries the access path forward.
ALTER TABLE practice_lead
    DROP FOREIGN KEY IF EXISTS fk_practice_lead_practice;

ALTER TABLE practice_lead
    DROP INDEX IF EXISTS idx_practice_lead_practice;

ALTER TABLE practice_lead
    DROP COLUMN IF EXISTS practice_code;

-- 5c) The four unconstrained code columns (no FK, no index on any of them). ---
--     Each is an exact duplicate of a uuid twin the application has maintained
--     since Phase 2; the twins were re-verified drift-free at authoring time.
ALTER TABLE `user`
    DROP COLUMN IF EXISTS practice;

ALTER TABLE user_practice_history
    DROP COLUMN IF EXISTS practice;

ALTER TABLE sales_lead
    DROP COLUMN IF EXISTS practice;

--     questionnaire.target_practices: the code array. Phase 5's Questionnaire
--     entity derives targetPractices from target_practice_uuids at read time
--     (@Transient), so the stored array has no reader left.
ALTER TABLE questionnaire
    DROP COLUMN IF EXISTS target_practices;


-- =============================================================================
-- 6) The canonical rename, expressed as a FOLD of the Part 1 display codes.
--    display_code has carried the canonical value since V418; this collapses
--    the two columns onto it. SA→IA, BA→BU, DEV→TECH.
--
--    The predicate keys on the three LEGACY codes, NOT on "code <> display_code".
--    The latter reads naturally but is a RULE rather than a one-shot migration:
--    PracticeService.create/update let an admin legitimately set code and
--    displayCode independently, so a later divergent row would make this
--    statement re-fire on the next replay — silently rewriting a live
--    practice's storage key (which §9 could not repair), or aborting boot on a
--    uk_practice_code collision. Keyed on the legacy codes it is a true fixed
--    point: after the first run the predicate can never match again.
--
--    Guarded on display_code still existing, so this file stays replayable
--    after the V430 micro-step drops that column (idiom mirrors V425 §3b).
--
--    Everything downstream follows automatically: the warehouse views resolve
--    the code through the registry off practice_uuid, so the next BI refresh
--    emits canonical codes with no view change (see the header's 24h-gap note).
-- =============================================================================

SET @practice_has_display_code := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'practice'
      AND COLUMN_NAME = 'display_code'
);
SET @practice_fold := IF(@practice_has_display_code > 0,
    'UPDATE practice SET code = display_code WHERE code IN (''SA'', ''BA'', ''DEV'')',
    'DO 0');
PREPARE practice_fold_stmt FROM @practice_fold;
EXECUTE practice_fold_stmt;
DEALLOCATE PREPARE practice_fold_stmt;


-- =============================================================================
-- 7) PK swap: the registry becomes uuid-keyed.
--    Target shape: PRIMARY KEY (uuid), UNIQUE uk_practice_code (code),
--    uq_practice_display_code retained.
--
--    Sequence matters and was validated against a scratch replica of this exact
--    schema: the swap runs as ONE statement so `practice` is never without a
--    primary key, and uq_practice_uuid is dropped only AFTERWARDS — by then
--    PRIMARY KEY (uuid) backs fk_tpa_practice (and §3's five new FKs), which
--    InnoDB repoints silently. Dropping the unique index first fails with
--    "Cannot drop index 'uq_practice_uuid': needed in a foreign key constraint".
--
--    No native IF NOT EXISTS exists for PRIMARY KEY, so the whole swap is
--    guarded by an information_schema probe (idiom mirrors V425 §3b / V329).
--    The guard asserts uuid is the SOLE primary key column, not merely part of
--    a composite one — a composite PK would otherwise skip the swap and then
--    strand the file on the uq_practice_uuid drop below.
-- =============================================================================

SET @practice_pk_is_uuid := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'practice'
      AND INDEX_NAME = 'PRIMARY'
      AND COLUMN_NAME = 'uuid'
      AND SEQ_IN_INDEX = 1
      AND (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'practice'
             AND INDEX_NAME = 'PRIMARY') = 1
);
SET @practice_pk_swap := IF(@practice_pk_is_uuid = 0,
    'ALTER TABLE practice DROP PRIMARY KEY, ADD PRIMARY KEY (uuid), ADD UNIQUE KEY uk_practice_code (code)',
    'DO 0');
PREPARE practice_pk_stmt FROM @practice_pk_swap;
EXECUTE practice_pk_stmt;
DEALLOCATE PREPARE practice_pk_stmt;

--    Now redundant: PRIMARY KEY (uuid) provides the uniqueness and the FK index.
ALTER TABLE practice
    DROP INDEX IF EXISTS uq_practice_uuid;


-- =============================================================================
-- 8) Mat tables: in-place code re-key, closing the 24h refresh gap (header).
--    These are the only four base tables outside the operational core that
--    store a practice code; verified by a value sweep across every string
--    column of every base table.
--    'UD' values are deliberately left alone — the member token outlives the
--    row. Idempotent by construction: each UPDATE keys on the legacy value it
--    replaces, and no target value is another statement's source, so a re-run
--    matches zero rows and the updates cannot chain.
--
--    Two of these mats also embed the code inside their surrogate id
--    (fact_employee_monthly.employee_month_id,
--    fact_revenue_budget.revenue_budget_id). Those are NOT rewritten here and
--    briefly disagree with the re-keyed column: harmless, because both tables
--    are TRUNCATE + INSERT..SELECT refreshed (never upserted on that id) and
--    nothing in the backend reads either id. The next refresh regenerates them
--    canonically.
-- =============================================================================

UPDATE fact_employee_monthly_mat   SET practice_id     = 'IA'   WHERE practice_id     = 'SA';
UPDATE fact_employee_monthly_mat   SET practice_id     = 'BU'   WHERE practice_id     = 'BA';
UPDATE fact_employee_monthly_mat   SET practice_id     = 'TECH' WHERE practice_id     = 'DEV';

UPDATE fact_opex_mat               SET practice_id     = 'IA'   WHERE practice_id     = 'SA';
UPDATE fact_opex_mat               SET practice_id     = 'BU'   WHERE practice_id     = 'BA';
UPDATE fact_opex_mat               SET practice_id     = 'TECH' WHERE practice_id     = 'DEV';

UPDATE fact_project_financials_mat SET service_line_id = 'IA'   WHERE service_line_id = 'SA';
UPDATE fact_project_financials_mat SET service_line_id = 'BU'   WHERE service_line_id = 'BA';
UPDATE fact_project_financials_mat SET service_line_id = 'TECH' WHERE service_line_id = 'DEV';

UPDATE fact_revenue_budget_mat     SET service_line_id = 'IA'   WHERE service_line_id = 'SA';
UPDATE fact_revenue_budget_mat     SET service_line_id = 'BU'   WHERE service_line_id = 'BA';
UPDATE fact_revenue_budget_mat     SET service_line_id = 'TECH' WHERE service_line_id = 'DEV';


-- =============================================================================
-- NOT done here, by design:
--   * practice.display_code and practice.type are NOT dropped — the draining
--     5A task still reads both. Trailing micro-step (V430 + a one-line entity
--     change) once 5A has fully drained. See the header and spec §1.6.K.
--   * No warehouse view is recreated for the rename: every one resolves its
--     code through the registry off practice_uuid, so §6's fold propagates to
--     them at the next refresh with no DDL. V427 recreated all seven precisely
--     so this phase would not have to.
--   * No stored procedure changes: sp_refresh_fact_tables and
--     sp_refresh_opex_mat_post_pass pass column names through, and both
--     snapshot procs moved to the uuid join in V427.
--   * fact_pipeline_snapshot keeps its practice_uuid column and gains no FK.
-- =============================================================================
