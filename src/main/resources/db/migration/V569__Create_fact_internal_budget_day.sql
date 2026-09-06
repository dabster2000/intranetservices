-- JK Team 2.0 / WP3 — daily demand from APPROVED internal assignments (spec §4.3.3).
--
-- A separate fact table, never rows in fact_budget_day: that table's unique key is
-- (useruuid, document_date, contractuuid) and its entity eagerly joins Contract and Client,
-- neither of which an internal assignment has. Keeping internal hours out of the
-- rate-bearing fact table makes "demand, never revenue" structural. Unioned at the read
-- layer only (/users/budgets/lite?includeInternal=true).
--
-- Written by InternalBudgetCalculatingExecutor (delete-then-insert per user and day, after
-- the contract budget step): hours_per_week / 5 on weekdays, then stacked against what the
-- contract budgets left of that day's net availability.
CREATE TABLE IF NOT EXISTS fact_internal_budget_day (
    id                        INT          NOT NULL AUTO_INCREMENT,
    useruuid                  VARCHAR(36)  NOT NULL,
    document_date             DATE         NOT NULL,
    year                      INT          NOT NULL,
    month                     INT          NOT NULL,
    day                       INT          NOT NULL,
    internal_assignment_uuid  VARCHAR(36)  NOT NULL,
    companyuuid               VARCHAR(36)  NULL,
    budget_hours              DOUBLE       NOT NULL,
    budget_hours_unadjusted   DOUBLE       NOT NULL,
    strategic                 TINYINT(1)   NOT NULL DEFAULT 0,
    last_update               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_fact_internal_budget_day_grain (useruuid, document_date, internal_assignment_uuid),
    KEY idx_fact_internal_budget_day_date (document_date),
    KEY idx_fact_internal_budget_day_user_month (useruuid, year, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
