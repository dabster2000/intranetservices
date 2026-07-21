-- =============================================================================
-- V424: Practice Part 2, Phase 1 — UUID foundation (dual-key core + triggers).
--
-- Purely ADDITIVE. After this migration the system behaves byte-identically; it
-- just carries a uuid on every practice reference alongside the existing code.
-- `practice.code` stays the primary key — the uuid is a unique attribute during
-- the dual-key window and only becomes the key in Phase 5.
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §4.1 (target model), §4.2 (provenance columns), §4.4 waves 0–1, §1.6.G.
-- Plan:  docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md
--        Phase 1.
--
-- This file covers the dual-key FOUNDATION: practice.uuid, the five durable
-- tables' practice_uuid twin columns + backfills, the user_practice_history
-- provenance columns, and the recreated V407 trigger family that keeps the code
-- and uuid columns from ever disagreeing. New structures (team_practice_assignment,
-- questionnaire/fact_pipeline_snapshot re-keys, the practice_settings drop and
-- the practice_lead user FK) live in V425.
--
-- ── IDEMPOTENCY (mandatory — this file re-runs) ──────────────────────────────
--   Two re-run scenarios matter:
--     1. repair-at-start re-runs the file on a DB where it is already applied.
--     2. the nightly ~02:00 prod→staging refresh strips every unpromoted
--        migration (this one) AND all triggers, then the next boot re-applies.
--   Consequences honoured throughout:
--     * ADD COLUMN / ADD KEY guarded IF NOT EXISTS; backfills keyed on IS NULL;
--       DROP TRIGGER IF EXISTS + CREATE.
--     * Every backfill DERIVES the uuid from the code column via a join to the
--       registry — never a hard-coded uuid. So each re-run converges even though
--       practice.uuid is minted fresh (UUID()) each time the column is re-added
--       after a refresh strip. Within any single run everything is internally
--       consistent; the uuid values are simply not stable across days until this
--       promotes to production (documented, not a bug).
--
-- Rollback: drop the added columns/keys and the recreated triggers. Only derived
-- (code→uuid) values are lost; the code columns remain the source of truth.
-- =============================================================================


-- 1) practice.uuid: stable surrogate identity, unique, populated. --------------
--    `code` stays PK; uuid is a plain UNIQUE attribute until Phase 5. Existing
--    rows are minted here; new rows get their uuid from PracticeService.create()
--    (entity maps uuid as an insertable column).
ALTER TABLE practice
    ADD COLUMN IF NOT EXISTS uuid VARCHAR(36) NULL AFTER code;

UPDATE practice SET uuid = UUID() WHERE uuid IS NULL;

ALTER TABLE practice
    MODIFY COLUMN uuid VARCHAR(36) NOT NULL;
ALTER TABLE practice
    ADD UNIQUE KEY IF NOT EXISTS uq_practice_uuid (uuid);


-- 2) Dual practice_uuid columns on the five durable tables. -------------------
--    Nullable (NULL/unknown code → NULL uuid). Backfilled by INNER JOIN to the
--    registry so only resolvable codes get a uuid (the inverse is guaranteed:
--    no uuid is ever set for a NULL or unregistered code). Indexed only where
--    the code column is itself indexed (rule from the task). NO strict FK yet —
--    strict FKs arrive in Phase 5 (spec §4.4 wave 4).
--
--    UD is a real registry row, so `user.practice = 'UD'` backfills to UD's uuid
--    — it is NOT special-cased here (operational NULL is Phase 4).

-- 2a) user.practice (unindexed → no index on the twin). Trigger-mirrored below.
ALTER TABLE `user`
    ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice;
UPDATE `user` u
    JOIN practice p ON p.code = u.practice
SET u.practice_uuid = p.uuid
WHERE u.practice_uuid IS NULL;

-- 2b) user_practice_history.practice (unindexed alone → no index on the twin).
ALTER TABLE user_practice_history
    ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice;
UPDATE user_practice_history h
    JOIN practice p ON p.code = h.practice
SET h.practice_uuid = p.uuid
WHERE h.practice_uuid IS NULL;

-- 2c) team.practice_code (indexed via fk_team_practice → index the twin).
ALTER TABLE team
    ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice_code;
ALTER TABLE team
    ADD KEY IF NOT EXISTS idx_team_practice_uuid (practice_uuid);
UPDATE team t
    JOIN practice p ON p.code = t.practice_code
SET t.practice_uuid = p.uuid
WHERE t.practice_uuid IS NULL;

-- 2d) practice_lead.practice_code (indexed as (practice_code, startdate) →
--     mirror the index shape on the twin).
ALTER TABLE practice_lead
    ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice_code;
ALTER TABLE practice_lead
    ADD KEY IF NOT EXISTS idx_practice_lead_practice_uuid (practice_uuid, startdate);
UPDATE practice_lead pl
    JOIN practice p ON p.code = pl.practice_code
SET pl.practice_uuid = p.uuid
WHERE pl.practice_uuid IS NULL;

-- 2e) sales_lead.practice (unindexed → no index on the twin).
ALTER TABLE sales_lead
    ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice;
UPDATE sales_lead sl
    JOIN practice p ON p.code = sl.practice
SET sl.practice_uuid = p.uuid
WHERE sl.practice_uuid IS NULL;


-- 3) user_practice_history provenance columns (spec §4.2). --------------------
--    No writer yet: Phase 2's PracticeSyncService populates them. Triggers leave
--    them NULL (the transition's cause is recorded by the application, not the
--    DB). source (the existing NOT NULL column) keeps its trigger semantics.
ALTER TABLE user_practice_history
    ADD COLUMN IF NOT EXISTS source_team_uuid VARCHAR(36) NULL AFTER source,
    ADD COLUMN IF NOT EXISTS updated_by       VARCHAR(36) NULL AFTER source_team_uuid;


-- 4) Trigger mirror — the invariant-keeper. -----------------------------------
--    Recreates the V407 family (extended) so the code and uuid columns can never
--    disagree during the dual-key window:
--      * NEW: BEFORE INSERT / BEFORE UPDATE on `user` set NEW.practice_uuid from
--        the registry (NULL-safe: `code = NULL` yields NULL). This keeps
--        user.practice_uuid mirrored no matter which writer touches the row
--        (UserService is the only application writer; V419-style migration
--        UPDATEs and staging sync are covered too).
--      * The three AFTER triggers keep their exact V407 semantics — half-open
--        intervals, OLD.practice <=> NEW.practice change detection, same-day
--        collapse, `source` values — and additionally carry practice_uuid
--        (taken straight from NEW.practice_uuid, already set by the BEFORE
--        trigger) onto every history row. The new provenance columns
--        (source_team_uuid, updated_by) are intentionally left NULL by triggers.
--    DROP IF EXISTS + CREATE for idempotency (the refresh strips triggers; this
--    block is the trigger definition of record until Phase 2 drops the family).

DROP TRIGGER IF EXISTS trg_user_practice_uuid_before_insert;
DROP TRIGGER IF EXISTS trg_user_practice_uuid_before_update;
DROP TRIGGER IF EXISTS trg_user_practice_history_after_insert;
DROP TRIGGER IF EXISTS trg_user_practice_history_after_update;
DROP TRIGGER IF EXISTS trg_user_practice_history_after_delete;

DELIMITER $$

-- 4a) Mirror practice_uuid on the row itself (NULL-safe). ----------------------
CREATE TRIGGER trg_user_practice_uuid_before_insert
BEFORE INSERT ON `user`
FOR EACH ROW
BEGIN
    SET NEW.practice_uuid = (SELECT p.uuid FROM practice p WHERE p.code = NEW.practice);
END$$

CREATE TRIGGER trg_user_practice_uuid_before_update
BEFORE UPDATE ON `user`
FOR EACH ROW
BEGIN
    SET NEW.practice_uuid = (SELECT p.uuid FROM practice p WHERE p.code = NEW.practice);
END$$

-- 4b) History maintenance (V407 semantics + practice_uuid). --------------------
CREATE TRIGGER trg_user_practice_history_after_insert
AFTER INSERT ON `user`
FOR EACH ROW
BEGIN
    IF NEW.practice IS NOT NULL THEN
        INSERT INTO user_practice_history
            (uuid, useruuid, practice, practice_uuid, effective_from, effective_to, recorded_at, source)
        VALUES
            (UUID(), NEW.uuid, NEW.practice, NEW.practice_uuid, CURRENT_DATE, NULL, CURRENT_TIMESTAMP(6), 'USER_INSERT_TRIGGER')
        ON DUPLICATE KEY UPDATE
            practice = VALUES(practice),
            practice_uuid = VALUES(practice_uuid),
            effective_to = NULL,
            recorded_at = VALUES(recorded_at),
            source = VALUES(source);
    END IF;
END$$

CREATE TRIGGER trg_user_practice_history_after_update
AFTER UPDATE ON `user`
FOR EACH ROW
BEGIN
    DECLARE v_open_uuid VARCHAR(36) DEFAULT NULL;
    DECLARE v_open_from DATE DEFAULT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_open_uuid = NULL;

    IF NOT (OLD.practice <=> NEW.practice) THEN
        SELECT uuid, effective_from
          INTO v_open_uuid, v_open_from
          FROM user_practice_history
         WHERE useruuid = NEW.uuid
           AND effective_to IS NULL
         ORDER BY effective_from DESC
         LIMIT 1;

        IF v_open_uuid IS NOT NULL AND v_open_from = CURRENT_DATE THEN
            IF NEW.practice IS NULL THEN
                DELETE FROM user_practice_history WHERE uuid = v_open_uuid;
            ELSE
                UPDATE user_practice_history
                   SET practice = NEW.practice,
                       practice_uuid = NEW.practice_uuid,
                       recorded_at = CURRENT_TIMESTAMP(6),
                       source = 'USER_UPDATE_TRIGGER'
                 WHERE uuid = v_open_uuid;
            END IF;
        ELSE
            IF v_open_uuid IS NOT NULL THEN
                UPDATE user_practice_history
                   SET effective_to = CURRENT_DATE,
                       recorded_at = CURRENT_TIMESTAMP(6)
                 WHERE uuid = v_open_uuid;
            END IF;

            IF NEW.practice IS NOT NULL THEN
                INSERT INTO user_practice_history
                    (uuid, useruuid, practice, practice_uuid, effective_from, effective_to, recorded_at, source)
                VALUES
                    (UUID(), NEW.uuid, NEW.practice, NEW.practice_uuid, CURRENT_DATE, NULL,
                     CURRENT_TIMESTAMP(6), 'USER_UPDATE_TRIGGER');
            END IF;
        END IF;
    END IF;
END$$

CREATE TRIGGER trg_user_practice_history_after_delete
AFTER DELETE ON `user`
FOR EACH ROW
BEGIN
    UPDATE user_practice_history
       SET effective_to = CASE
               WHEN effective_from < CURRENT_DATE THEN CURRENT_DATE
               ELSE DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY)
           END,
           recorded_at = CURRENT_TIMESTAMP(6)
     WHERE useruuid = OLD.uuid
       AND effective_to IS NULL;
END$$

DELIMITER ;
