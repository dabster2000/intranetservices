-- ====================================================================
-- V415: Teamlead bonus editable calculation sources.
--
-- Adds the two admin-editable source tables backing the Teamlead Bonus
-- admin dashboard's member toggles and leader exclusions:
--   * teamlead_bonus_member_override   — force a member-month in/out of the calc.
--   * teamlead_bonus_leader_exclusion  — exclude a leader from a team for a FY.
--
-- Conventions (per project rules & V414 style):
--   * All DDL is idempotent (CREATE TABLE IF NOT EXISTS) — repair-at-start re-runs migrations.
--   * No FK constraints (cross-aggregate refs are plain UUID columns).
--   * utf8mb4 / InnoDB.
-- ====================================================================

-- 1) Per-member inclusion overrides (per team, per user, per month) --------
CREATE TABLE IF NOT EXISTS teamlead_bonus_member_override (
    uuid        VARCHAR(36)  NOT NULL,
    teamuuid    VARCHAR(36)  NOT NULL,
    useruuid    VARCHAR(36)  NOT NULL,
    month       CHAR(6)      NOT NULL,   -- YYYYMM
    included    TINYINT(1)   NOT NULL,   -- 1 = force-include, 0 = force-exclude
    note        VARCHAR(500) NULL,
    created_at  DATETIME     NULL,
    created_by  VARCHAR(100) NULL,
    updated_at  DATETIME     NULL,
    updated_by  VARCHAR(100) NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_teamlead_bonus_member_override_team_user_month (teamuuid, useruuid, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) Per-leader exclusions (per fiscal year, per team, per user) -----------
CREATE TABLE IF NOT EXISTS teamlead_bonus_leader_exclusion (
    uuid        VARCHAR(36)  NOT NULL,
    fiscal_year INT          NOT NULL,
    teamuuid    VARCHAR(36)  NOT NULL,
    useruuid    VARCHAR(36)  NOT NULL,
    note        VARCHAR(500) NULL,
    created_at  DATETIME     NULL,
    created_by  VARCHAR(100) NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_teamlead_bonus_leader_exclusion_fy_team_user (fiscal_year, teamuuid, useruuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
