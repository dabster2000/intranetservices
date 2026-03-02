-- =============================================================================
-- Migration V203: Add cost_type column to accounting_accounts
--
-- Purpose:
--   Introduce a cost_type classification column to accounting_accounts so that
--   fact views and service layers can filter GL accounts by semantic cost type
--   instead of brittle hardcoded account-code ranges (e.g., 3000-5999).
--
-- New column: cost_type VARCHAR(20) NOT NULL DEFAULT 'OTHER'
--
-- Allowed values (enum enforced at application layer via CostType.java):
--   REVENUE      — Sales / invoiced revenue (currently 'Varesalg' groupname)
--   DIRECT_COSTS — Project delivery costs  ('Direkte omkostninger' groupname)
--   SALARIES     — Staff/admin payroll (salary = true accounts)
--   OPEX         — Operating expenses (rent, software, marketing, admin)
--   IGNORE       — Balance sheet, WIP, tax — excluded from all P&L views
--   OTHER        — Unclassified; treated as IGNORE until manually classified
--
-- Default 'OTHER' is safe: unclassified accounts are excluded from all fact
-- views until a human reviews and assigns the correct type via the admin UI.
--
-- Relationship to existing flags (both flags are RETAINED — orthogonal):
--   shared  — still controls distribution (split across companies by headcount)
--   salary  — still controls salary pool capping in IntercompanyCalcService
--   cost_type — controls P&L line placement (which fact view an account feeds)
--
-- Backwards compatibility:
--   Adding a NOT NULL column with a DEFAULT does not break existing reads.
--   Java entities (AccountingAccount) will pick up the column after V204 maps
--   values; until then every row reads as 'OTHER' which is treated as IGNORE.
--
-- Idempotent: IF NOT EXISTS guard — safe to re-run.
-- =============================================================================

ALTER TABLE accounting_accounts
    ADD COLUMN IF NOT EXISTS cost_type VARCHAR(20) NOT NULL DEFAULT 'OTHER';

-- ---------------------------------------------------------------------------
-- Index: support future admin UI queries filtering by cost_type
-- (e.g., "show me all SALARIES accounts across companies")
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_aa_cost_type
    ON accounting_accounts (cost_type);
