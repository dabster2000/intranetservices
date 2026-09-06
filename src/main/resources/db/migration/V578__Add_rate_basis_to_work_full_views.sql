-- JK Team 2.0 / WP4c — billability discriminator (spec §4.4c.2, decision D3).
--
-- WHY
-- ---
-- `work.billable` has been dead since Dec 2023, so `rate > 0` became the de-facto
-- billability test. V572 made a *declared* zero-rate consultant line legal (the "føl"
-- pricing model), and at that moment `rate > 0` stopped being able to answer the
-- question it was being asked: a deliberately-free client hour and an hour with no
-- contract at all both resolve to rate 0 here (see the IFNULL below), and are
-- therefore indistinguishable.
--
-- D3 (2026-09-06): a sold-at-zero hour IS sold. `rate > 0` keeps meaning *revenue*;
-- billability moves to `rate_basis <> 'NO_CONTRACT'`.
--
-- Deriving it in the view — rather than making each consumer join
-- contract_consultants — is the whole point: the sites migrated in this release are
-- the ones that exist today, and the next query written inherits correct behaviour
-- instead of having to remember the join.
--
-- SAFETY
-- ------
-- Both views are recreated verbatim from V259 with two additions: `cc.zero_rate_reason`
-- inside the `ccc` derived table, and a trailing `rate_basis` column. The new column is
-- appended last so positional consumers are unaffected. Nothing else changes — `rate`,
-- `discount`, join predicates and the 2021-07-01 floor are byte-identical to V259.
--
-- A matched line with rate = 0 and no zero_rate_reason cannot exist (V85 enforced
-- rate > 0 until V572, which requires a reason for any zero), but the CASE falls through
-- to 'NO_CONTRACT' if one ever appears — the conservative choice, since that preserves
-- today's behaviour rather than silently promoting an undeclared zero to billable.
--
-- Views are metadata-only: CREATE OR REPLACE VIEW takes no table lock and rewrites no
-- rows. Rollback is re-applying V259.

-- =========================================================================
-- 1. work_full
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
    w.updated_at AS updated_at,
    CASE
        WHEN ccc.contractuuid IS NULL         THEN 'NO_CONTRACT'
        WHEN IFNULL(ccc.rate, 0) > 0          THEN 'BILLABLE'
        WHEN ccc.zero_rate_reason IS NOT NULL THEN 'ZERO_DECLARED'
        ELSE 'NO_CONTRACT'
    END AS rate_basis
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
        cc.zero_rate_reason AS zero_rate_reason,
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
-- 2. work_full_optimized
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
    w.updated_at,
    CASE
        WHEN ccc.contractuuid IS NULL         THEN 'NO_CONTRACT'
        WHEN IFNULL(ccc.rate, 0) > 0          THEN 'BILLABLE'
        WHEN ccc.zero_rate_reason IS NOT NULL THEN 'ZERO_DECLARED'
        ELSE 'NO_CONTRACT'
    END AS rate_basis
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
        cc.zero_rate_reason,
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
