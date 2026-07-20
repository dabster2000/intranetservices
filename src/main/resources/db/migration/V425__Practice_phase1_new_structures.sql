-- =============================================================================
-- V425: Practice Part 2, Phase 1 — new structures & cleanups.
--
-- Companion to V424 (dual-key foundation). This file adds the adjacent objects:
--   * team_practice_assignment — the temporal team↔practice source of truth
--     (spec §4.1), seeded with each team's current practice; nothing writes it
--     until Phase 2.
--   * questionnaire.target_practice_uuids — a uuid twin of the code JSON array
--     (dual-key, not a rewrite: the code array stays authoritative until the
--     targeting reader switches in Phase 3).
--   * fact_pipeline_snapshot — re-keyed to practice_uuid AND the now-dead code
--     column dropped (Hans, 2026-07-19: the column has zero readers, so this is
--     behaviour-preserving; the 2 stored procs are recreated to write the uuid).
--   * practice_settings — dropped (empty since V419, zero readers).
--   * practice_lead.useruuid — strict FK → user(uuid) (belt-and-suspenders over
--     the Phase 0 service existence check).
--
-- Spec:  docs/superpowers/specs/2026-07-19-practice-data-model-design.md
--        §4.1, §4.2, §4.4 wave 1, §4.7 (fact_pipeline_snapshot), §1.6.G.
-- Plan:  docs/superpowers/plans/2026-07-19-practice-part2-phased-rollout.md Phase 1.
--
-- Idempotency (same re-run rules as V424): CREATE TABLE IF NOT EXISTS, ADD COLUMN
-- IF NOT EXISTS, seeds guarded by NOT EXISTS, backfills keyed on IS NULL and
-- DERIVED from code columns via a registry join (so they converge when
-- practice.uuid is re-minted after a refresh strip), CREATE OR REPLACE PROCEDURE,
-- DROP ... IF EXISTS, and the fact_pipeline_snapshot backfill guarded by an
-- information_schema check so it is a no-op once the code column is gone
-- (idiom mirrors V296).
--
-- Rollback: drop team_practice_assignment; drop questionnaire.target_practice_uuids;
-- fact_pipeline_snapshot practice column is recreatable from V228 + the V229 procs;
-- practice_settings is recreatable from V253; drop fk_practice_lead_user.
-- =============================================================================


-- 1) team_practice_assignment — temporal team↔practice association (spec §4.1). -
--    uuid PK, FK team_uuid → team(uuid), FK practice_uuid → practice(uuid),
--    startdate NOT NULL, enddate NULL (=current), house audit columns (Phase 0
--    Auditable pattern). Seeded one OPEN row per team with a non-NULL
--    practice_code, startdate = the V418 backfill date. Backend entity mapped;
--    nothing writes it until Phase 2's PracticeSyncService.
CREATE TABLE IF NOT EXISTS team_practice_assignment (
    uuid          VARCHAR(36)  NOT NULL PRIMARY KEY,
    team_uuid     VARCHAR(36)  NOT NULL,
    practice_uuid VARCHAR(36)  NOT NULL,
    startdate     DATE         NOT NULL,
    enddate       DATE         NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    modified_by   VARCHAR(255) NULL,
    KEY idx_tpa_team (team_uuid, startdate),
    KEY idx_tpa_practice (practice_uuid),
    CONSTRAINT fk_tpa_team     FOREIGN KEY (team_uuid)     REFERENCES team (uuid),
    CONSTRAINT fk_tpa_practice FOREIGN KEY (practice_uuid) REFERENCES practice (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Seed: one open row per practice-assigned team. practice_uuid derives from the
-- team's practice_code via the registry (converges under uuid re-mint). The
-- NOT EXISTS guard keeps re-runs from duplicating rows on the same DB.
INSERT INTO team_practice_assignment
    (uuid, team_uuid, practice_uuid, startdate, enddate, created_at, updated_at, created_by, modified_by)
SELECT UUID(), t.uuid, p.uuid, '2026-07-19', NULL, NOW(6), NOW(6), 'V425_MIGRATION', NULL
FROM team t
JOIN practice p ON p.code = t.practice_code
WHERE t.practice_code IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM team_practice_assignment tpa WHERE tpa.team_uuid = t.uuid);


-- 2) questionnaire.target_practice_uuids — dual-key JSON twin (spec §4.4 wave 1).
--    target_practices (a JSON array of code strings) stays AUTHORITATIVE; the
--    targeting reader still matches codes until Phase 3, so we add a uuid twin
--    rather than rewrite the code array (rewriting now would silently break
--    questionnaire targeting). Backfill maps each code → its registry uuid,
--    preserving element order; unregistered codes are dropped (INNER JOIN).
ALTER TABLE questionnaire
    ADD COLUMN IF NOT EXISTS target_practice_uuids TEXT NULL AFTER target_practices;

UPDATE questionnaire q
SET q.target_practice_uuids = (
        SELECT CONCAT('[', COALESCE(GROUP_CONCAT(JSON_QUOTE(p.uuid) ORDER BY jt.ord SEPARATOR ','), ''), ']')
        FROM JSON_TABLE(q.target_practices, '$[*]'
                        COLUMNS (ord FOR ORDINALITY, code VARCHAR(20) PATH '$')) jt
        JOIN practice p ON p.code = jt.code
    )
WHERE q.target_practices IS NOT NULL
  AND JSON_VALID(q.target_practices)
  AND JSON_LENGTH(q.target_practices) > 0
  AND q.target_practice_uuids IS NULL;


-- 3) fact_pipeline_snapshot — re-key to practice_uuid, drop the dead code column.
--    The practice column has ZERO readers (SalesService reads other columns; no
--    frontend reads it); its only writers are the two snapshot procs. Decision
--    (Hans, 2026-07-19): re-key AND drop the code column for a clean end state —
--    behaviour-preserving and canary-safe (no app task reads/writes it; the procs
--    are replaced atomically here). The 2 JK history rows have no registry row,
--    so they resolve to NULL practice_uuid (frozen, consistent with the JK
--    retirement in V419).

-- 3a) Add the uuid column + mirror the old (snapshot_month, practice) index shape.
ALTER TABLE fact_pipeline_snapshot
    ADD COLUMN IF NOT EXISTS practice_uuid VARCHAR(36) NULL AFTER practice;
ALTER TABLE fact_pipeline_snapshot
    ADD KEY IF NOT EXISTS idx_fps_month_practice_uuid (snapshot_month, practice_uuid);

-- 3b) Backfill historical rows via the registry — guarded so it is a no-op once
--     the legacy `practice` column has already been dropped (repair re-run).
SET @fps_has_practice := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'fact_pipeline_snapshot'
      AND COLUMN_NAME = 'practice'
);
SET @fps_backfill := IF(@fps_has_practice > 0,
    'UPDATE fact_pipeline_snapshot fps JOIN practice p ON p.code = fps.practice SET fps.practice_uuid = p.uuid WHERE fps.practice_uuid IS NULL',
    'DO 0');
PREPARE fps_stmt FROM @fps_backfill;
EXECUTE fps_stmt;
DEALLOCATE PREPARE fps_stmt;

-- 3c) Recreate the two snapshot procedures to write practice_uuid instead of the
--     code. Bodies are byte-for-byte the V229 procs except: the INSERT column
--     `practice` → `practice_uuid`, a LEFT JOIN to the registry (preg) resolving
--     sl.practice → preg.uuid at snapshot time, and the SELECT `sl.practice` →
--     `preg.uuid`. Resolving from the code (not sl.practice_uuid) keeps the
--     snapshot correct even though sl.practice_uuid is not maintained until
--     Phase 3. CREATE OR REPLACE is idempotent.

DELIMITER $$

CREATE OR REPLACE PROCEDURE sp_snapshot_pipeline(IN p_snapshot_month CHAR(6))
BEGIN
    -- Delete existing snapshot for idempotency
    DELETE FROM fact_pipeline_snapshot WHERE snapshot_month = p_snapshot_month;

    INSERT INTO fact_pipeline_snapshot (
        snapshot_id, snapshot_month, opportunity_uuid, client_uuid, company_uuid,
        stage_id, practice_uuid, rate, period_months, allocation, consultant_count,
        is_extension, expected_revenue_dkk, probability_pct, weighted_pipeline_dkk
    )
    SELECT
        CONCAT(p_snapshot_month, '-', sl.uuid) AS snapshot_id,
        p_snapshot_month,
        sl.uuid,
        sl.clientuuid,
        -- Company from lead manager's userstatus (same pattern as fact_pipeline V180)
        COALESCE(
            (SELECT us.companyuuid
             FROM userstatus us
             WHERE us.useruuid = sl.leadmanager
               AND us.statusdate <= COALESCE(sl.closedate, CURDATE())
             ORDER BY us.statusdate DESC
             LIMIT 1),
            'd8894494-2fb4-4f72-9e05-e6032e6dd691'
        ) AS company_uuid,
        sl.status AS stage_id,
        preg.uuid AS practice_uuid,
        sl.rate,
        sl.period AS period_months,
        sl.allocation,
        GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid), 1
        )) AS consultant_count,
        sl.extension AS is_extension,
        -- Expected monthly revenue = rate * standard_hours * allocation% * consultants
        (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc2 WHERE slc2.leaduuid = sl.uuid), 1
        ))) AS expected_revenue_dkk,
        CASE sl.status
            WHEN 'DETECTED'    THEN 10
            WHEN 'QUALIFIED'   THEN 25
            WHEN 'SHORTLISTED' THEN 40
            WHEN 'PROPOSAL'    THEN 50
            WHEN 'NEGOTIATION' THEN 75
            ELSE 10
        END AS probability_pct,
        -- Weighted = expected * probability / 100
        (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
            (SELECT COUNT(*) FROM sales_lead_consultant slc3 WHERE slc3.leaduuid = sl.uuid), 1
        ))) * (CASE sl.status
            WHEN 'DETECTED'    THEN 10
            WHEN 'QUALIFIED'   THEN 25
            WHEN 'SHORTLISTED' THEN 40
            WHEN 'PROPOSAL'    THEN 50
            WHEN 'NEGOTIATION' THEN 75
            ELSE 10
        END / 100.0) AS weighted_pipeline_dkk
    FROM sales_lead sl
    LEFT JOIN practice preg ON preg.code = sl.practice
    WHERE sl.status NOT IN ('WON', 'LOST')
      AND sl.rate > 0
      AND sl.period > 0;
END$$


CREATE OR REPLACE PROCEDURE sp_backfill_pipeline_snapshots()
BEGIN
    DECLARE v_counter INT DEFAULT 0;
    DECLARE v_snapshot_month CHAR(6);
    DECLARE v_month_start DATE;
    DECLARE v_month_end DATE;
    DECLARE v_existing INT;

    -- Iterate from 12 months ago up to (and including) the current month
    WHILE v_counter <= 12 DO
        SET v_month_start = DATE_ADD(
            DATE(CONCAT(YEAR(CURDATE()), '-', LPAD(MONTH(CURDATE()), 2, '0'), '-01')),
            INTERVAL (v_counter - 12) MONTH
        );
        SET v_month_end   = LAST_DAY(v_month_start);
        SET v_snapshot_month = DATE_FORMAT(v_month_start, '%Y%m');

        -- Only backfill if no rows exist for this month
        SELECT COUNT(*) INTO v_existing
        FROM fact_pipeline_snapshot
        WHERE snapshot_month = v_snapshot_month;

        IF v_existing = 0 THEN
            INSERT INTO fact_pipeline_snapshot (
                snapshot_id, snapshot_month, opportunity_uuid, client_uuid,
                company_uuid, stage_id, practice_uuid, rate, period_months,
                allocation, consultant_count, is_extension,
                expected_revenue_dkk, probability_pct, weighted_pipeline_dkk
            )
            SELECT
                CONCAT(v_snapshot_month, '-', sl.uuid) AS snapshot_id,
                v_snapshot_month,
                sl.uuid,
                sl.clientuuid,
                COALESCE(
                    (SELECT us.companyuuid
                     FROM userstatus us
                     WHERE us.useruuid = sl.leadmanager
                       AND us.statusdate <= v_month_end
                     ORDER BY us.statusdate DESC
                     LIMIT 1),
                    'd8894494-2fb4-4f72-9e05-e6032e6dd691'
                ) AS company_uuid,
                -- Determine the stage the lead was in at that point in time
                CASE
                    -- WON leads that were won AFTER this month: they were likely in NEGOTIATION
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 'NEGOTIATION'
                    -- LOST leads that were lost AFTER this month: use lost_at_stage
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN COALESCE(sl.lost_at_stage, 'DETECTED')
                    -- Still-active leads: use current status
                    ELSE sl.status
                END AS stage_id,
                preg.uuid AS practice_uuid,
                sl.rate,
                sl.period AS period_months,
                sl.allocation,
                GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc WHERE slc.leaduuid = sl.uuid), 1
                )) AS consultant_count,
                sl.extension AS is_extension,
                -- Expected revenue
                (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc2 WHERE slc2.leaduuid = sl.uuid), 1
                ))) AS expected_revenue_dkk,
                -- Probability based on reconstructed stage
                CASE
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 75  -- NEGOTIATION probability
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN CASE COALESCE(sl.lost_at_stage, 'DETECTED')
                            WHEN 'DETECTED'    THEN 10
                            WHEN 'QUALIFIED'   THEN 25
                            WHEN 'SHORTLISTED' THEN 40
                            WHEN 'PROPOSAL'    THEN 50
                            WHEN 'NEGOTIATION' THEN 75
                            ELSE 10
                        END
                    ELSE CASE sl.status
                        WHEN 'DETECTED'    THEN 10
                        WHEN 'QUALIFIED'   THEN 25
                        WHEN 'SHORTLISTED' THEN 40
                        WHEN 'PROPOSAL'    THEN 50
                        WHEN 'NEGOTIATION' THEN 75
                        ELSE 10
                    END
                END AS probability_pct,
                -- Weighted pipeline
                (sl.rate * 160.33 * (sl.allocation / 100.0) * GREATEST(1, COALESCE(
                    (SELECT COUNT(*) FROM sales_lead_consultant slc3 WHERE slc3.leaduuid = sl.uuid), 1
                ))) * (CASE
                    WHEN sl.status = 'WON' AND sl.won_date > v_month_end
                        THEN 75
                    WHEN sl.status = 'LOST' AND sl.last_updated > v_month_end
                        THEN CASE COALESCE(sl.lost_at_stage, 'DETECTED')
                            WHEN 'DETECTED'    THEN 10
                            WHEN 'QUALIFIED'   THEN 25
                            WHEN 'SHORTLISTED' THEN 40
                            WHEN 'PROPOSAL'    THEN 50
                            WHEN 'NEGOTIATION' THEN 75
                            ELSE 10
                        END
                    ELSE CASE sl.status
                        WHEN 'DETECTED'    THEN 10
                        WHEN 'QUALIFIED'   THEN 25
                        WHEN 'SHORTLISTED' THEN 40
                        WHEN 'PROPOSAL'    THEN 50
                        WHEN 'NEGOTIATION' THEN 75
                        ELSE 10
                    END
                END / 100.0) AS weighted_pipeline_dkk
            FROM sales_lead sl
            LEFT JOIN practice preg ON preg.code = sl.practice
            WHERE sl.created <= v_month_end
              AND sl.rate > 0
              AND sl.period > 0
              AND (
                  -- Currently active leads (not WON/LOST)
                  sl.status NOT IN ('WON', 'LOST')
                  -- WON leads that were won AFTER this month (so they were active during it)
                  OR (sl.status = 'WON' AND sl.won_date > v_month_end)
                  -- LOST leads that were lost AFTER this month
                  OR (sl.status = 'LOST' AND sl.last_updated > v_month_end)
              );
        END IF;

        SET v_counter = v_counter + 1;
    END WHILE;
END$$

DELIMITER ;

-- 3d) Drop the now-dead code column and its index (the uuid twin replaces both).
ALTER TABLE fact_pipeline_snapshot
    DROP INDEX IF EXISTS idx_fps_month_practice;
ALTER TABLE fact_pipeline_snapshot
    DROP COLUMN IF EXISTS practice;


-- 4) Drop the empty practice_settings table (spec §4.4 wave 1). ----------------
--    Empty since V419, its Java trio deleted in Part 1, zero readers verified
--    across both repos' src (only migration history + a doc-comment reference
--    remain). Recreatable from V253 if ever needed.
DROP TABLE IF EXISTS practice_settings;


-- 5) practice_lead.useruuid strict FK → user(uuid) (spec §4.1). ----------------
--    Belt-and-suspenders over the Phase 0 service existence check. Verified: all
--    existing practice_lead rows resolve (0 rows locally and on staging at
--    authoring time). ADD CONSTRAINT ... IF NOT EXISTS keeps re-runs idempotent.
ALTER TABLE practice_lead
    ADD CONSTRAINT fk_practice_lead_user FOREIGN KEY IF NOT EXISTS (useruuid) REFERENCES `user` (uuid);
