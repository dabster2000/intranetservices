-- =============================================================================
-- Migration V154: Create dim_date (Date Dimension Table)
--
-- Purpose:
--   Provides a pre-computed calendar lookup table used by stored procedures
--   (Phase 2+) to replace Java-based date calculations with set-based SQL.
--
-- Grain: One row per calendar day
-- Range: 2014-01-01 through 2034-12-31 (21 years)
--
-- Fiscal year convention: July 1 through June 30
--   e.g., FY2025 = 2025-07-01 to 2026-06-30
--
-- Key columns:
--   date_key           - Primary key (DATE)
--   fiscal_year        - FY start year (2025 for Jul 2025 - Jun 2026)
--   fiscal_month       - 1=Jul, 2=Aug, ..., 12=Jun
--   fiscal_quarter     - 1-4 (Q1=Jul-Sep, Q2=Oct-Dec, Q3=Jan-Mar, Q4=Apr-Jun)
--   is_weekend         - 1 if Saturday or Sunday
--   is_company_shutdown- Reserved for company-wide shutdown days (default 0)
--   working_days_in_month - Count of weekday (Mon-Fri) days in the calendar month
--
-- Note: Uses cross-joined sequence tables instead of recursive CTE to avoid
--       MariaDB max_recursive_iterations limit (default 1000).
-- =============================================================================

CREATE TABLE dim_date (
    date_key              DATE        PRIMARY KEY,
    year                  SMALLINT    NOT NULL,
    month                 TINYINT     NOT NULL,
    day                   TINYINT     NOT NULL,
    day_of_week           TINYINT     NOT NULL COMMENT '1=Mon, 2=Tue, ..., 7=Sun (ISO)',
    is_weekend            TINYINT     NOT NULL,
    is_company_shutdown   TINYINT     NOT NULL DEFAULT 0,
    fiscal_year           SMALLINT    NOT NULL COMMENT 'FY starts July 1',
    fiscal_month          TINYINT     NOT NULL COMMENT '1=Jul, 2=Aug, ..., 12=Jun',
    fiscal_quarter        TINYINT     NOT NULL,
    week_number           TINYINT     NOT NULL,
    working_days_in_month TINYINT     NOT NULL DEFAULT 0,
    INDEX idx_dim_date_year_month   (year, month),
    INDEX idx_dim_date_fiscal       (fiscal_year, fiscal_month),
    INDEX idx_dim_date_weekend      (is_weekend)
) ENGINE=InnoDB;

-- Populate dim_date using cross-joined digit tables (no recursive CTE needed).
-- Generates sequence 0..9999, then filters to valid date range.
INSERT INTO dim_date (
    date_key, year, month, day, day_of_week,
    is_weekend, fiscal_year, fiscal_month, fiscal_quarter,
    week_number, working_days_in_month
)
SELECT
    d AS date_key,
    YEAR(d) AS year,
    MONTH(d) AS month,
    DAY(d) AS day,
    WEEKDAY(d) + 1 AS day_of_week,
    IF(WEEKDAY(d) >= 5, 1, 0) AS is_weekend,
    IF(MONTH(d) >= 7, YEAR(d), YEAR(d) - 1) AS fiscal_year,
    IF(MONTH(d) >= 7, MONTH(d) - 6, MONTH(d) + 6) AS fiscal_month,
    CEIL(IF(MONTH(d) >= 7, MONTH(d) - 6, MONTH(d) + 6) / 3.0) AS fiscal_quarter,
    WEEK(d, 3) AS week_number,
    0 AS working_days_in_month
FROM (
    SELECT DATE_ADD('2014-01-01', INTERVAL seq DAY) AS d
    FROM (
        SELECT (t4.n * 1000 + t3.n * 100 + t2.n * 10 + t1.n) AS seq
        FROM
            (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
             UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) t1,
            (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
             UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) t2,
            (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
             UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) t3,
            (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
             UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) t4
    ) seq_table
    WHERE DATE_ADD('2014-01-01', INTERVAL seq DAY) <= '2034-12-31'
) dates;

-- Update working_days_in_month: count of Mon-Fri days per calendar month
UPDATE dim_date dd
SET working_days_in_month = (
    SELECT COUNT(*)
    FROM dim_date dd2
    WHERE dd2.year = dd.year
      AND dd2.month = dd.month
      AND dd2.is_weekend = 0
);
