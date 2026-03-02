-- =============================================================================
-- Migration V213: Rename primaryskilltype → practice on user table
--
-- Purpose:
--   Align the column name with the current domain language: "practice" is the
--   correct business term for the consultant's primary skill/practice area.
--   Also removes the legacy primary_skill_level integer column which was
--   superseded by the temporal user_career_level table in V151/V178.
--
-- Changes:
--   user.primaryskilltype  → user.practice   (rename, VARCHAR, no type change)
--   user.primary_skill_level               → dropped
--
-- Downstream impact:
--   All views that SELECT u.primaryskilltype will break until V214 recreates
--   them. V213 and V214 must be applied in sequence within the same Flyway run.
--
-- Rollback strategy:
--   ALTER TABLE user RENAME COLUMN practice TO primaryskilltype;
--   The primary_skill_level column is dropped (was already unused after V178).
--   Restoring it requires adding the column back and re-populating from a backup
--   if needed — acceptable since it was deprecated.
--
-- Impact assessment:
--   Quarkus entities: User.java — update @Column mapping primaryskilltype → practice
--   Views: fact_user_utilization, fact_employee_monthly, fact_revenue_budget,
--          fact_backlog, fact_project_financials, fact_salary_monthly, consultant
--          (all recreated in V214/V215)
-- =============================================================================

-- Step 1: Rename primaryskilltype → practice
ALTER TABLE user RENAME COLUMN primaryskilltype TO practice;

-- Step 2: Drop the deprecated primary_skill_level column
--   This integer (1-5) was replaced by the temporal user_career_level table.
--   The consultant view (V179) exposed it for backward compatibility; V215
--   removes it from the view as well.
ALTER TABLE user DROP COLUMN primary_skill_level;
