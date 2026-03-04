-- =============================================================================
-- Migration V226: Fix duplicate rows in consultant VIEW
--
-- Problem:
--   The consultant VIEW joins temporal tables (userstatus, salary,
--   user_career_level) using MAX(date) subqueries. When multiple rows
--   share the same MAX date, LEFT JOINs produce a Cartesian product,
--   causing duplicate employee rows on the staffing page.
--
-- Verified duplicates:
--   - user_career_level: Andreas Joachim Nielsen has 2 entries on 2026-02-15
--   - userstatus: Gunilla Lethenborg has 2 entries on 2025-07-01
--
-- Fixes:
--   1. Remove duplicate data rows (keep latest by uuid ordering)
--   2. Add UNIQUE constraints to prevent future duplicates
--   3. Recreate consultant VIEW using ROW_NUMBER() for guaranteed 1 row/user
-- =============================================================================

-- ─── Step 1: Remove duplicate user_career_level rows ────────────────────────
-- For rows sharing (useruuid, active_from), keep the one with the latest uuid
-- (alphabetically last = most recently generated UUID v4).
DELETE ucl FROM user_career_level ucl
INNER JOIN (
    SELECT useruuid, active_from, MAX(uuid) AS keep_uuid
    FROM user_career_level
    GROUP BY useruuid, active_from
    HAVING COUNT(*) > 1
) dups ON ucl.useruuid = dups.useruuid
      AND ucl.active_from = dups.active_from
      AND ucl.uuid != dups.keep_uuid;

-- ─── Step 2: Remove duplicate userstatus rows ───────────────────────────────
-- For rows sharing (useruuid, statusdate), keep the one with the latest uuid.
DELETE us FROM userstatus us
INNER JOIN (
    SELECT useruuid, statusdate, MAX(uuid) AS keep_uuid
    FROM userstatus
    GROUP BY useruuid, statusdate
    HAVING COUNT(*) > 1
) dups ON us.useruuid = dups.useruuid
      AND us.statusdate = dups.statusdate
      AND us.uuid != dups.keep_uuid;

-- ─── Step 3: Remove duplicate salary rows ───────────────────────────────────
-- For rows sharing (useruuid, activefrom), keep the one with the latest uuid.
DELETE s FROM salary s
INNER JOIN (
    SELECT useruuid, activefrom, MAX(uuid) AS keep_uuid
    FROM salary
    GROUP BY useruuid, activefrom
    HAVING COUNT(*) > 1
) dups ON s.useruuid = dups.useruuid
      AND s.activefrom = dups.activefrom
      AND s.uuid != dups.keep_uuid;

-- ─── Step 4: Add UNIQUE constraints to prevent future duplicates ────────────
ALTER TABLE user_career_level
    ADD UNIQUE INDEX uq_career_level_user_date (useruuid, active_from);

ALTER TABLE userstatus
    ADD UNIQUE INDEX uq_userstatus_user_date (useruuid, statusdate);

ALTER TABLE salary
    ADD UNIQUE INDEX uq_salary_user_date (useruuid, activefrom);

-- ─── Step 5: Recreate consultant VIEW with ROW_NUMBER() ────────────────────
-- Using ROW_NUMBER() guarantees exactly 1 row per user even if data
-- anomalies exist, providing defense-in-depth.
DROP VIEW IF EXISTS consultant;

CREATE OR REPLACE VIEW consultant AS
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
    u.practice,
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
