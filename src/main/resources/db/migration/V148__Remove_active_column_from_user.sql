-- V148: Remove active column from user table
-- =========================================================================
-- The 'active' column on the user table is obsolete. User activity/status
-- is determined by the userstatus table (ACTIVE, TERMINATED, etc.).
-- This migration removes the column and updates the consultant view.
-- =========================================================================

-- =========================================================================
-- PART 1: Drop and recreate the consultant view WITHOUT the active column
-- =========================================================================
-- The consultant view joins user data with the latest userstatus record.
-- We must recreate it without referencing u.active.

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
    u.primaryskilltype,
    u.primary_skill_level,
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
    SELECT us1.*
    FROM userstatus us1
    INNER JOIN (
        SELECT useruuid, MAX(statusdate) AS max_statusdate
        FROM userstatus
        GROUP BY useruuid
    ) latest ON us1.useruuid = latest.useruuid AND us1.statusdate = latest.max_statusdate
) us ON u.uuid = us.useruuid
LEFT JOIN (
    SELECT s1.*
    FROM salary s1
    INNER JOIN (
        SELECT useruuid, MAX(activefrom) AS max_activefrom
        FROM salary
        GROUP BY useruuid
    ) latest_sal ON s1.useruuid = latest_sal.useruuid AND s1.activefrom = latest_sal.max_activefrom
) s ON u.uuid = s.useruuid;

-- =========================================================================
-- PART 2: Drop the active column from the user table
-- =========================================================================

ALTER TABLE user DROP COLUMN active;
