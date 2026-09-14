-- ============================================================================
-- V606 — a star on the relationships tab is a seat on the plan
--
-- Spec: docs/specs/intra-crm-relationships-people-2026-09-14.md §3.6, §5.1
-- Domain: aggregates/crm/plan
--
-- WHAT THIS DOES
--   1. client_plan_stakeholder gains person_uuid (nullable, indexed, NO FK) --
--      the link from a plan seat back to the registry row V604 created.
--   2. client_plan_stakeholder.unit becomes NULL-able, keeping its exact type.
--   3. client_plan_relation loses current_level, last_interaction and source.
--
-- BOTH TABLES HELD 0 ROWS IN PRODUCTION ON 2026-09-14. Nothing below destroys
--   any data, because there is none to destroy. That is also why the three
--   dropped columns are dropped rather than deprecated: there is no migration of
--   values to argue about and no reader to break.
--
-- WHY A STAR NEEDS person_uuid
--   Starring a person means "this one matters on this account", and the plan is
--   where that already lives. Until now a stakeholder row was a name and a seat
--   typed into the plan; from here a star copies name and title off the registry
--   row at the moment of starring and keeps a pointer back to it, so the two
--   surfaces agree about who they are talking about. The copy is deliberate: the
--   plan keeps its own sentence even after the person goes.
--
-- WHY NO FOREIGN KEY ON person_uuid — the spec asked for one and this does not
--   build it
--   Spec §3.6 wants FK -> account_person ON DELETE SET NULL. The problem is not
--   the semantics, it is the two databases. client_plan_stakeholder IS copied to
--   staging by sp_sync_prod_to_staging; account_person is NOT (V608 excludes
--   it). A constraint that holds in one environment and not the other is not a
--   constraint, it is a trap waiting for the first person who restores a
--   production dump into a database holding staging's own registry.
--
--   Strictly, the nightly sync itself would survive an FK -- PHASE 1 copies with
--   CREATE TABLE ... LIKE, which does not carry foreign keys across, and it runs
--   with FOREIGN_KEY_CHECKS = 0 anyway. Every other path that moves these rows
--   would not. The house rule that came out of that is the simple one: no FK
--   onto a table the sync does not carry.
--
--   The ON DELETE SET NULL the spec wanted happens in code instead.
--   CrmRetentionPurgeService (V608) NULLs person_uuid and name explicitly when
--   it deletes a person, which is the same outcome and is also the only one that
--   works for `name`, since a cascade cannot null a copied string.
--
-- WHY unit BECOMES NULL-ABLE
--   A stakeholder typed into the plan by hand has a unit -- "IT Operations",
--   "Indkøb". A person the registry knows has no such thing: the sources give a
--   name, sometimes a job title, never an org unit. Left NOT NULL, every star
--   would have to invent one, and the UI would render "CIO, " with an empty tail
--   on every starred row. The column keeps its exact type, VARCHAR(255); only
--   the nullability moves. MODIFY replaces a column definition wholesale, so the
--   full type is restated below on purpose -- anything left out would be
--   silently dropped.
--
-- WHY client_plan_relation LOSES THREE COLUMNS
--   current_level, last_interaction and source were the plan's own guess at how
--   well a colleague knows a stakeholder. That guess now has an owner: the
--   colleague themselves, through account_relation_claim (V605), and the
--   calendar, through the MET edges the relationships tab derives. Two stores of
--   the same fact drift, and the one that drifts is always the one nobody
--   updates. What stays on the table is the part only the account owner can say:
--   target_level, role, assessed_by and assessed_at -- the TARGET per colleague,
--   not the current state.
--
--   (current_level was never called `current`: V586's own comment records that
--   `current` is a MariaDB reserved word. The replacement, strength on
--   account_relation_claim, sidesteps the question entirely.)
--
-- METADATA LOCK — the check this cut owes, and its answer
--   V606 and V607 are the only ALTERs in the cut. A DDL migration that queues
--   behind an open transaction holds the metadata lock and hangs boot, which has
--   happened here before, so the question is asked every time rather than
--   assumed.
--
--   Neither table is held open at deploy time. client_plan_stakeholder and
--   client_plan_relation are written only by AccountPlanService, on a person
--   clicking save in the plan tab -- a short request-scoped transaction, not a
--   job and not a stream -- and read by the same service in single SELECTs. With
--   0 rows in both, MariaDB 10.11 does the ADD COLUMN instantly and the MODIFY
--   and DROPs as a rebuild of an empty table, so the lock is held for the
--   metadata change and nothing more.
--
-- RESERVED-WORD CHECK (MariaDB 10.11, written down because of the V534 `lines`
--   incident). One new column name: `person_uuid`. Not reserved. One new index
--   name: `idx_client_plan_stakeholder_person`, 34 characters, under the 64-char
--   identifier limit. The three dropped names introduce nothing. `LINES` -- the
--   word that broke V534 -- does not appear.
--
-- COLLATION: untouched. Both tables are already utf8mb4 / utf8mb4_general_ci
--   from V586 and person_uuid inherits the table default, so it joins
--   account_person.uuid without the ERROR 1267 a unicode_ci mix would produce at
--   runtime.
--
-- STAGING SYNC: not re-emitted here. client_plan_stakeholder and
--   client_plan_relation are already copied and stay copied; the single
--   re-emission of sp_sync_prod_to_staging for this cut is in V608. See V604's
--   header for why there is exactly one.
--
-- Idempotency: ADD COLUMN IF NOT EXISTS / DROP COLUMN IF EXISTS. MODIFY is
--   naturally idempotent -- it states the target shape, not a delta.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback (restores the SHAPE; both tables were empty, so there is no data to
--   restore and none is lost):
--   ALTER TABLE client_plan_relation
--       ADD COLUMN current_level TINYINT NOT NULL COMMENT '0-4; `current` is a MariaDB reserved word',
--       ADD COLUMN last_interaction DATE NULL,
--       ADD COLUMN source ENUM ('CONTRACT','CALENDAR','SIGNAL','SIGNAL_AND_CALENDAR') NOT NULL;
--   ALTER TABLE client_plan_stakeholder MODIFY unit VARCHAR(255) NOT NULL;
--   ALTER TABLE client_plan_stakeholder DROP COLUMN person_uuid;
--   (the index goes with the column)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The link from a plan seat back to the registry.
--
--    Nullable because every stakeholder row that exists today, and every one
--    typed by hand tomorrow, has no registry row behind it -- and because the
--    V608 purge NULLs this column rather than deleting the seat: a plan should
--    keep "we need somebody in procurement" after the person it was about is
--    erased.
-- ----------------------------------------------------------------------------
--    Column and index in one statement, so the metadata lock is taken once.
ALTER TABLE client_plan_stakeholder
    ADD COLUMN IF NOT EXISTS person_uuid CHAR(36) NULL
        COMMENT 'The account_person row a star was placed on. NULL = a seat typed into the plan by hand, or a person the V608 purge has erased. Deliberately NOT a foreign key: account_person is excluded from the staging sync and this table is copied'
        AFTER client_uuid,
    ADD KEY IF NOT EXISTS idx_client_plan_stakeholder_person (person_uuid);

-- ----------------------------------------------------------------------------
-- 2. A person the registry knows has no org unit, and the UI must not render a
--    title with an empty tail. Full type restated: MODIFY replaces the whole
--    definition.
-- ----------------------------------------------------------------------------
ALTER TABLE client_plan_stakeholder
    MODIFY unit VARCHAR(255) NULL
        COMMENT 'The org unit a hand-typed stakeholder sits in. NULL when the row came from a star: the sources give a name and sometimes a job title, never a unit';

-- ----------------------------------------------------------------------------
-- 3. The plan stops guessing at how well a colleague knows somebody. That fact
--    now has one owner -- the colleague's own claim (V605) and the calendar --
--    and target_level, role, assessed_by and assessed_at stay, because the
--    TARGET is the part only the account owner can set.
-- ----------------------------------------------------------------------------
ALTER TABLE client_plan_relation
    DROP COLUMN IF EXISTS current_level,
    DROP COLUMN IF EXISTS last_interaction,
    DROP COLUMN IF EXISTS source;
