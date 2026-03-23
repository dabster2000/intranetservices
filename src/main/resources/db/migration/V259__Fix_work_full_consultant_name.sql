-- V259: Fix work_full and work_full_optimized views to use actual user name
-- instead of contract_consultants.name which is often empty.
-- The Consultant column in Excel exports was showing blank because cc.name
-- is a label field, not the user's real name.

-- =========================================================================
-- 1. Recreate work_full view with user.firstname + user.lastname
-- =========================================================================
CREATE OR REPLACE VIEW work_full AS
SELECT
    w.uuid AS uuid,
    w.useruuid AS useruuid,
    w.workas AS workas,
    w.taskuuid AS taskuuid,
    w.workduration AS workduration,
    w.registered AS registered,
    w.billable AS billable,
    w.paid_out AS paid_out,
    IFNULL(ccc.rate, 0) AS rate,
    IFNULL(ccc.discount, 0) AS discount,
    CONCAT(u.firstname, ' ', u.lastname) AS name,
    t.projectuuid AS projectuuid,
    p.clientuuid AS clientuuid,
    ccc.contractuuid AS contractuuid,
    ccc.companyuuid AS contract_company_uuid,
    (SELECT us_inner.companyuuid FROM userstatus us_inner
     WHERE us_inner.useruuid = w.useruuid AND us_inner.statusdate <= w.registered
     ORDER BY us_inner.statusdate DESC LIMIT 1) AS consultant_company_uuid,
    (SELECT us_inner.type FROM userstatus us_inner
     WHERE us_inner.useruuid = w.useruuid AND us_inner.statusdate <= w.registered
     ORDER BY us_inner.statusdate DESC LIMIT 1) AS type,
    w.comments AS comments,
    w.updated_at AS updated_at
FROM work w
LEFT JOIN user u ON u.uuid = w.useruuid
LEFT JOIN task t ON w.taskuuid = t.uuid
LEFT JOIN project p ON t.projectuuid = p.uuid
LEFT JOIN (
    SELECT
        cc.rate AS rate,
        c.uuid AS contractuuid,
        cc.activefrom AS activefrom,
        cc.activeto AS activeto,
        c.companyuuid AS companyuuid,
        cp.projectuuid AS projectuuid,
        cc.useruuid AS useruuid,
        CAST(NULLIF(cti.value, '') AS UNSIGNED) AS discount
    FROM contract_project cp
    LEFT JOIN contract_consultants cc ON cp.contractuuid = cc.contractuuid
    LEFT JOIN contracts c ON cc.contractuuid = c.uuid
    LEFT JOIN contract_type_items cti ON c.uuid = cti.contractuuid
) ccc ON ccc.useruuid = IF(w.workas IS NOT NULL, w.workas, w.useruuid)
    AND p.uuid = ccc.projectuuid
    AND ccc.activefrom <= w.registered
    AND ccc.activeto >= w.registered
WHERE w.registered >= '2021-07-01';

-- =========================================================================
-- 2. Recreate work_full_optimized view with user.firstname + user.lastname
-- =========================================================================
CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `work_full_optimized` AS
WITH ranked_user_status AS (
    SELECT
        useruuid,
        statusdate,
        companyuuid,
        type,
        ROW_NUMBER() OVER (PARTITION BY useruuid ORDER BY statusdate DESC) as rn
    FROM userstatus
),
work_with_status AS (
    SELECT
        w.*,
        (
            SELECT us.companyuuid
            FROM ranked_user_status us
            WHERE us.useruuid = w.useruuid
              AND us.statusdate <= w.registered
            ORDER BY us.statusdate DESC
            LIMIT 1
        ) as consultant_company_uuid,
        (
            SELECT us.type
            FROM ranked_user_status us
            WHERE us.useruuid = w.useruuid
              AND us.statusdate <= w.registered
            ORDER BY us.statusdate DESC
            LIMIT 1
        ) as consultant_type
    FROM work w
    WHERE w.registered >= '2021-07-01'
)
SELECT
    w.uuid,
    w.useruuid,
    w.workas,
    w.taskuuid,
    w.workduration,
    w.registered,
    w.billable,
    w.paid_out,
    IFNULL(ccc.rate, 0) AS rate,
    IFNULL(ccc.discount, 0) AS discount,
    CONCAT(u.firstname, ' ', u.lastname) AS name,
    t.projectuuid,
    p.clientuuid,
    ccc.contractuuid,
    ccc.companyuuid AS contract_company_uuid,
    w.consultant_company_uuid,
    w.consultant_type as type,
    w.comments,
    w.updated_at
FROM work_with_status w
LEFT JOIN user u ON u.uuid = w.useruuid
LEFT JOIN task t ON w.taskuuid = t.uuid
LEFT JOIN project p ON t.projectuuid = p.uuid
LEFT JOIN (
    SELECT
        cc.rate,
        c.uuid AS contractuuid,
        cc.activefrom,
        cc.activeto,
        c.companyuuid,
        cp.projectuuid,
        cc.useruuid,
        CAST(NULLIF(cti.value, '') AS UNSIGNED) AS discount
    FROM contract_project cp
    LEFT JOIN contract_consultants cc ON cp.contractuuid = cc.contractuuid
    LEFT JOIN contracts c ON cc.contractuuid = c.uuid
    LEFT JOIN contract_type_items cti ON c.uuid = cti.contractuuid
) ccc ON (
    ccc.useruuid = IF(w.workas IS NOT NULL, w.workas, w.useruuid)
    AND p.uuid = ccc.projectuuid
    AND ccc.activefrom <= w.registered
    AND ccc.activeto >= w.registered
);
