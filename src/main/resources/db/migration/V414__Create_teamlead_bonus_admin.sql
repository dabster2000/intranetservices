-- ====================================================================
-- V414: Teamlead bonus admin model.
--
-- Adds the FY-versioned configuration, admin adjustments, salary-exclusion
-- overrides and payout ledger backing the Teamlead Bonus admin dashboard,
-- plus a settings-tab registration in page_registry.
--
-- Conventions (per project rules):
--   * All DDL is idempotent (CREATE TABLE IF NOT EXISTS).
--   * No FK constraints (cross-aggregate refs are plain UUID columns).
--   * LONGTEXT for JSON snapshots (never @JdbcTypeCode(JSON) — boot crash).
--   * page_registry INSERT mirrors V349/V374 (ON DUPLICATE KEY UPDATE).
-- ====================================================================

-- 1) FY-versioned configuration ---------------------------------------
CREATE TABLE IF NOT EXISTS teamlead_bonus_config (
    fiscal_year                   INT            NOT NULL,
    pool_share_percent            DECIMAL(6,4)   NOT NULL DEFAULT 0.0500,
    min_util_threshold            DECIMAL(6,4)   NOT NULL DEFAULT 0.6500,
    production_threshold_annual   DECIMAL(14,2)  NOT NULL DEFAULT 1100000.00,
    production_commission_percent DECIMAL(6,4)   NOT NULL DEFAULT 0.2000,
    team_factor_tier1             DECIMAL(4,2)   NOT NULL DEFAULT 1.00,
    team_factor_tier2             DECIMAL(4,2)   NOT NULL DEFAULT 1.50,
    team_factor_tier3             DECIMAL(4,2)   NOT NULL DEFAULT 2.00,
    team_factor_tier2_from        DECIMAL(6,2)   NOT NULL DEFAULT 7.00,
    team_factor_tier3_from        DECIMAL(6,2)   NOT NULL DEFAULT 11.00,
    overskud_override             DECIMAL(16,2)  NULL,
    overskud_override_note        VARCHAR(500)   NULL,
    updated_at                    DATETIME       NULL,
    updated_by                    VARCHAR(100)   NULL,
    PRIMARY KEY (fiscal_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed FY2025 with the code defaults (no-op if the row already exists).
INSERT IGNORE INTO teamlead_bonus_config (fiscal_year) VALUES (2025);

-- 2) Admin adjustments (split / prepaid / util override) --------------
CREATE TABLE IF NOT EXISTS teamlead_bonus_adjustment (
    uuid            VARCHAR(36)   NOT NULL,
    fiscal_year     INT           NOT NULL,
    useruuid        VARCHAR(36)   NOT NULL,
    adjustment_type VARCHAR(30)   NOT NULL,   -- SPLIT_BONUS | PREPAID_DEDUCTION | UTIL_OVERRIDE
    amount          DECIMAL(14,2) NULL,
    util_override   DECIMAL(6,4)  NULL,
    note            VARCHAR(500)  NULL,
    created_at      DATETIME      NULL,
    created_by      VARCHAR(100)  NULL,
    updated_at      DATETIME      NULL,
    updated_by      VARCHAR(100)  NULL,
    PRIMARY KEY (uuid),
    KEY idx_teamlead_bonus_adjustment_fy_user (fiscal_year, useruuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Hard backstop for the "max one UTIL_OVERRIDE per (fiscal_year, useruuid)" rule: a generated
-- key that is non-NULL only for UTIL_OVERRIDE rows, under a UNIQUE index (NULLs never collide,
-- so SPLIT_BONUS / PREPAID_DEDUCTION may still have multiple rows per user and year). The
-- service's check-then-insert is only a friendly 409; this constraint closes the race.
ALTER TABLE teamlead_bonus_adjustment
    ADD COLUMN IF NOT EXISTS util_override_key VARCHAR(50)
        AS (CASE WHEN adjustment_type = 'UTIL_OVERRIDE'
                 THEN CONCAT(fiscal_year, ':', useruuid) END) STORED,
    ADD UNIQUE KEY IF NOT EXISTS uq_teamlead_bonus_adjustment_util_override (util_override_key);

-- 3) Salary-exclusion overrides ---------------------------------------
CREATE TABLE IF NOT EXISTS teamlead_bonus_salary_exclusion (
    uuid        VARCHAR(36)  NOT NULL,
    fiscal_year INT          NOT NULL,
    useruuid    VARCHAR(36)  NOT NULL,
    mode        VARCHAR(20)  NOT NULL,   -- EXCLUDE_SALARY | INCLUDE_SALARY
    note        VARCHAR(500) NULL,
    created_at  DATETIME     NULL,
    created_by  VARCHAR(100) NULL,
    updated_at  DATETIME     NULL,
    updated_by  VARCHAR(100) NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_teamlead_bonus_salary_exclusion_fy_user (fiscal_year, useruuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) Payout ledger (fund-once per FY + leader) ------------------------
CREATE TABLE IF NOT EXISTS teamlead_bonus_payouts (
    uuid                    VARCHAR(36)   NOT NULL,
    fiscal_year             INT           NOT NULL,
    useruuid                VARCHAR(36)   NOT NULL,
    payout_month            DATE          NOT NULL,
    pool_amount             DECIMAL(14,2) NULL,
    production_amount       DECIMAL(14,2) NULL,
    split_amount            DECIMAL(14,2) NULL,
    prepaid_deduction       DECIMAL(14,2) NULL,
    total_amount            DECIMAL(14,2) NULL,
    pool_lump_sum_uuid      VARCHAR(36)   NULL,
    production_lump_sum_uuid VARCHAR(36)  NULL,
    split_lump_sum_uuid     VARCHAR(36)   NULL,
    calculation_snapshot    LONGTEXT      NULL,
    created_at              DATETIME      NULL,
    created_by              VARCHAR(100)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_teamlead_bonus_payouts_fy_user (fiscal_year, useruuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5) Settings-tab registration (mirrors V349/V374) --------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-teamlead-bonus', 'Teamlead Bonus', 1, '/settings?tab=teamlead-bonus', 'ADMIN', 120, 'SETTINGS', 'Users', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    is_visible     = VALUES(is_visible),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    is_external    = VALUES(is_external),
    external_url   = VALUES(external_url);
