-- V95: Fix work_full_optimized view to handle empty string discount values
-- This migration fixes the "Truncated incorrect INTEGER value: ''" error
-- that occurs when contract_type_items.value contains empty strings

-- =========================================================================
-- Recreate the work_full_optimized view with proper empty string handling
-- =========================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED DEFINER=`admin`@`%` SQL SECURITY DEFINER VIEW `work_full_optimized` AS
WITH ranked_user_status AS (
    -- Get all user statuses with ranking for each user
    SELECT
        useruuid,
        statusdate,
        companyuuid,
        type,
        ROW_NUMBER() OVER (PARTITION BY useruuid ORDER BY statusdate DESC) as rn
    FROM userstatus
),
work_with_status AS (
    -- Join work with the appropriate user status based on date
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
    ccc.name,
    t.projectuuid,
    p.clientuuid,
    ccc.contractuuid,
    ccc.companyuuid AS contract_company_uuid,
    w.consultant_company_uuid,
    w.consultant_type as type,
    w.comments,
    w.updated_at
FROM work_with_status w
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
        cc.name,
        -- FIX: Use NULLIF to convert empty strings to NULL before casting
        -- This prevents "Truncated incorrect INTEGER value: ''" errors
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
