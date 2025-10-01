-- V83: Work Performance Optimizations
-- This migration adds indexes and creates an optimized view for work data queries
-- to improve performance when fetching large date ranges (e.g., yearly data)

-- =========================================================================
-- PART 1: Add Missing Indexes for Performance
-- =========================================================================

-- Index for efficient user status lookups in work_full view subqueries
-- This dramatically improves the correlated subquery performance
CREATE INDEX IF NOT EXISTS idx_userstatus_lookup
ON userstatus(useruuid, statusdate DESC, companyuuid, type);

-- Composite index for work table to speed up date range queries
-- Covers the most common query pattern: registered date ranges with user filtering
CREATE INDEX IF NOT EXISTS idx_work_period_user
ON work(registered, useruuid, taskuuid, workduration);

-- Index to optimize contract consultant lookups by date range
CREATE INDEX IF NOT EXISTS idx_contract_consultants_lookup
ON contract_consultants(useruuid, contractuuid, activefrom, activeto, rate);

-- Index for contract_project joins
CREATE INDEX IF NOT EXISTS idx_contract_project_lookup
ON contract_project(contractuuid, projectuuid);

-- Additional index for work table to support billable work queries
-- Note: MariaDB doesn't support partial indexes with WHERE clause
CREATE INDEX IF NOT EXISTS idx_work_billable
ON work(registered, billable, useruuid);

-- Index for paid_out queries (for hourly workers)
CREATE INDEX IF NOT EXISTS idx_work_paidout
ON work(useruuid, taskuuid, paid_out);

-- =========================================================================
-- PART 2: Create Optimized View (MariaDB Compatible)
-- =========================================================================

-- Create an optimized version of work_full that uses window functions
-- This avoids the worst performing correlated subqueries
-- Note: MariaDB doesn't support LATERAL joins, so we use a different approach
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
        CAST(cti.value AS UNSIGNED) AS discount
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

-- =========================================================================
-- PART 3: Create Materialized View Table for Ultimate Performance
-- =========================================================================

-- Create a physical table to cache work_full data
-- This will be updated via application code or scheduled jobs
CREATE TABLE IF NOT EXISTS work_full_cache (
    uuid VARCHAR(36) NOT NULL,
    useruuid VARCHAR(36) NOT NULL,
    workas VARCHAR(36),
    taskuuid VARCHAR(36) NOT NULL,
    workduration DOUBLE,
    registered DATE NOT NULL,
    billable TINYINT(1) DEFAULT 1,
    paid_out DATETIME,
    rate DOUBLE DEFAULT 0,
    discount INT DEFAULT 0,
    name VARCHAR(150),
    projectuuid VARCHAR(36),
    clientuuid VARCHAR(36),
    contractuuid VARCHAR(36),
    contract_company_uuid VARCHAR(36),
    consultant_company_uuid VARCHAR(36),
    type VARCHAR(25),
    comments VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    cache_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    INDEX idx_cache_registered_user (registered, useruuid),
    INDEX idx_cache_user_task_reg (useruuid, taskuuid, registered),
    INDEX idx_cache_project (projectuuid, registered),
    INDEX idx_cache_contract (contractuuid, registered),
    INDEX idx_cache_updated (cache_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Materialized cache of work_full view for performance. Updated by scheduled jobs.';

-- =========================================================================
-- PART 4: Create Stored Procedure for Cache Refresh
-- =========================================================================

DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS `refresh_work_full_cache`(IN from_date DATE, IN to_date DATE)
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    -- Delete existing cache entries for the date range
    DELETE FROM work_full_cache
    WHERE registered >= from_date AND registered < to_date;

    -- Insert fresh data from the optimized view
    INSERT INTO work_full_cache (
        uuid, useruuid, workas, taskuuid, workduration, registered,
        billable, paid_out, rate, discount, name, projectuuid,
        clientuuid, contractuuid, contract_company_uuid,
        consultant_company_uuid, type, comments, updated_at, cache_updated_at
    )
    SELECT
        uuid, useruuid, workas, taskuuid, workduration, registered,
        billable, paid_out, rate, discount, name, projectuuid,
        clientuuid, contractuuid, contract_company_uuid,
        consultant_company_uuid, type, comments, updated_at, NOW()
    FROM work_full_optimized
    WHERE registered >= from_date AND registered < to_date;

    COMMIT;
END$$

DELIMITER ;

-- =========================================================================
-- PART 5: Update Statistics for Query Optimizer
-- =========================================================================

ANALYZE TABLE work;
ANALYZE TABLE userstatus;
ANALYZE TABLE contract_consultants;
ANALYZE TABLE contract_project;
ANALYZE TABLE contracts;
ANALYZE TABLE task;
ANALYZE TABLE project;