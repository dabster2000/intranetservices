-- =============================================================================
-- Migration V215: Update consultant view after primaryskilltype → practice rename
--
-- Purpose:
--   Recreate the consultant view (originally V179) replacing:
--   - u.primaryskilltype → u.practice
--   - Remove u.primary_skill_level (column was dropped in V213)
--
-- Source: V179__Update_consultant_view_career_level.sql
--
-- Changes:
--   1. Column u.primaryskilltype → u.practice  (column renamed in V213)
--   2. Column u.primary_skill_level removed     (column dropped in V213)
--      Previously kept for backward compatibility; now fully removed.
--
-- Quarkus impact:
--   Any @Entity or Panache query that mapped primary_skill_level from the
--   consultant view must be updated to use career_track + career_level instead.
--
-- Rollback strategy:
--   Re-run V179 view definition (which references the old column names).
--   Only safe after rolling back V213 as well.
-- =============================================================================

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
    SELECT us1.*
    FROM userstatus us1
    INNER JOIN (
        SELECT useruuid, MAX(statusdate) AS max_statusdate
        FROM userstatus
        WHERE statusdate <= CURDATE()
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
) s ON u.uuid = s.useruuid
LEFT JOIN (
    SELECT cl1.*
    FROM user_career_level cl1
    INNER JOIN (
        SELECT useruuid, MAX(active_from) AS max_active_from
        FROM user_career_level
        WHERE active_from <= CURDATE()
        GROUP BY useruuid
    ) latest_cl ON cl1.useruuid = latest_cl.useruuid AND cl1.active_from = latest_cl.max_active_from
) cl ON u.uuid = cl.useruuid;
