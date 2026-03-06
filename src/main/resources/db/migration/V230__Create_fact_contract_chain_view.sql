-- =============================================================================
-- Migration V230: Create fact_contract_chain view
--
-- Purpose:
--   Recursive CTE view that chains contracts via parentuuid to model
--   multi-contract engagements. Enables:
--   - Engagement lifecycle tracking (original contract through extensions)
--   - Extension rate analysis (how often do contracts get extended?)
--   - Client engagement duration and value analysis
--
-- Grain: One row per contract, enriched with chain/engagement-level metrics
--
-- Key design decisions:
--   - contracts.activefrom/activeto were removed in V183; derived from
--     MIN/MAX of contract_consultants.activefrom/activeto
--   - Only includes contracts with status IN ('SIGNED', 'TIME', 'BUDGET')
--     matching the fact_backlog filter convention (V121)
--   - Practice derived from dominant consultant on the contract (user.practice)
--   - Company derived from contract's own company reference
--
-- Dependencies:
--   - contracts table (parentuuid for chain linking)
--   - contract_consultants table (date range, rate, consultant count)
--   - user table (practice column, post-V213 rename from primaryskilltype)
--
-- Rollback: DROP VIEW IF EXISTS fact_contract_chain;
-- =============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_contract_chain` AS

WITH RECURSIVE
    -- 1) Derive activefrom/activeto per contract from contract_consultants
    contract_dates AS (
        SELECT
            contractuuid,
            MIN(activefrom) AS activefrom,
            MAX(activeto)   AS activeto,
            COUNT(DISTINCT useruuid) AS consultant_count,
            AVG(rate) AS avg_rate
        FROM contract_consultants
        GROUP BY contractuuid
    ),

    -- 2) Build contract chain from root (parentuuid IS NULL) to leaves
    contract_chain AS (
        -- Root contracts (no parent)
        SELECT
            c.uuid AS contract_uuid,
            c.uuid AS root_contract_uuid,
            0 AS chain_depth,
            c.clientuuid AS client_uuid,
            c.companyuuid AS company_uuid,
            c.status,
            c.parentuuid
        FROM contracts c
        WHERE c.parentuuid IS NULL
          AND c.status IN ('SIGNED', 'TIME', 'BUDGET')

        UNION ALL

        -- Extension contracts (have parent)
        SELECT
            c.uuid AS contract_uuid,
            cc.root_contract_uuid,
            cc.chain_depth + 1 AS chain_depth,
            c.clientuuid AS client_uuid,
            c.companyuuid AS company_uuid,
            c.status,
            c.parentuuid
        FROM contracts c
        INNER JOIN contract_chain cc ON c.parentuuid = cc.contract_uuid
        WHERE c.status IN ('SIGNED', 'TIME', 'BUDGET')
    ),

    -- 3) Engagement-level aggregates (per root contract chain)
    engagement_stats AS (
        SELECT
            cc2.root_contract_uuid,
            MIN(cd.activefrom) AS engagement_start,
            MAX(cd.activeto) AS engagement_end,
            COUNT(cc2.contract_uuid) AS contract_count_in_chain
        FROM contract_chain cc2
        LEFT JOIN contract_dates cd ON cc2.contract_uuid = cd.contractuuid
        GROUP BY cc2.root_contract_uuid
    ),

    -- 4) Dominant practice per contract (from assigned consultants)
    contract_practice AS (
        SELECT
            cc_inner.contractuuid AS contract_uuid,
            COALESCE(
                (SELECT u.practice
                 FROM contract_consultants cc2
                 JOIN user u ON cc2.useruuid = u.uuid
                 WHERE cc2.contractuuid = cc_inner.contractuuid
                   AND u.practice IS NOT NULL
                 GROUP BY u.practice
                 ORDER BY COUNT(*) DESC, SUM(cc2.hours) DESC
                 LIMIT 1),
                'UD'
            ) AS practice
        FROM contract_consultants cc_inner
        GROUP BY cc_inner.contractuuid
    )

SELECT
    ch.contract_uuid,
    ch.root_contract_uuid,
    ch.chain_depth,
    ch.client_uuid,
    ch.company_uuid,
    ch.status,

    -- Per-contract dates (derived from contract_consultants)
    cd.activefrom AS contract_start,
    cd.activeto AS contract_end,
    CASE
        WHEN cd.activefrom IS NOT NULL AND cd.activeto IS NOT NULL
            THEN TIMESTAMPDIFF(MONTH, cd.activefrom, cd.activeto)
        ELSE 0
    END AS duration_months,

    COALESCE(cd.consultant_count, 0) AS consultant_count,
    COALESCE(cd.avg_rate, 0) AS avg_rate,
    COALESCE(cp.practice, 'UD') AS practice,

    -- Engagement-level metrics (across entire chain)
    es.engagement_start,
    es.engagement_end,
    CASE
        WHEN es.engagement_start IS NOT NULL AND es.engagement_end IS NOT NULL
            THEN TIMESTAMPDIFF(MONTH, es.engagement_start, es.engagement_end)
        ELSE 0
    END AS engagement_duration_months,
    es.contract_count_in_chain,
    GREATEST(0, es.contract_count_in_chain - 1) AS extension_count,

    -- Is this contract itself an extension?
    CASE WHEN ch.chain_depth > 0 THEN 1 ELSE 0 END AS is_extension

FROM contract_chain ch
LEFT JOIN contract_dates cd ON ch.contract_uuid = cd.contractuuid
LEFT JOIN engagement_stats es ON ch.root_contract_uuid = es.root_contract_uuid
LEFT JOIN contract_practice cp ON ch.contract_uuid = cp.contract_uuid
ORDER BY ch.root_contract_uuid, ch.chain_depth;
