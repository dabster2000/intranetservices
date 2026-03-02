-- =============================================================================
-- Migration V204: Populate cost_type on accounting_accounts
--
-- Purpose:
--   Auto-classify all existing accounts from existing signals so that the
--   fact views work immediately after migration without requiring human review
--   for every row. Human corrections are made via the accounting admin UI
--   (PATCH /accounting/accounts/{uuid}/cost-type).
--
-- Classification priority (CLARIFIED DECISION — groupname wins over salary):
--
--   Priority 1 — groupname mapping (explicit accounting category wins):
--     'Varesalg'                        → REVENUE
--     'Direkte omkostninger'             → DIRECT_COSTS
--     'Igangværende arbejde'             → IGNORE  (WIP / balance sheet)
--     'Delte services'                   → OPEX
--     'Variable omkostninger'            → OPEX
--     'Lokaleomkostninger'               → OPEX
--     'Salgsfremmende omkostninger'      → OPEX
--     'Øvrige administrationsomk. i alt' → OPEX
--
--   Priority 2 — salary flag fallback (only for accounts whose groupname
--                did NOT map to any of the above categories):
--     salary = 1 AND cost_type still = 'OTHER' → SALARIES
--
--   Everything else → remains 'OTHER' (requires manual review)
--
-- Execution order:
--   Step 1: Apply groupname-based mappings (7 UPDATE statements)
--   Step 2: Apply salary=1 fallback for still-unclassified accounts
--
-- Estimated affected rows: ~290 accounts across 3 companies.
-- The majority of accounts will be classified; residual 'OTHER' rows are
-- expected for balance-sheet and miscellaneous accounts.
--
-- Rollback strategy:
--   Single-column reset: UPDATE accounting_accounts SET cost_type = 'OTHER';
--   Flyway clean + re-run V203+V204 restores original state.
--
-- =============================================================================

-- ---------------------------------------------------------------------------
-- STEP 1A: REVENUE — accounts in the 'Varesalg' category
-- These are sales/invoiced-revenue GL entries. Not run through cost splitter.
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'REVENUE'
WHERE ac.groupname = 'Varesalg'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1B: DIRECT_COSTS — accounts in the 'Direkte omkostninger' category
-- Project delivery costs (intercompany consultant invoices, subconsultants).
-- Not distributed — stays with origin company (no shared distribution).
-- Per spec: there are no DIRECT_COSTS accounts with shared = true.
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'DIRECT_COSTS'
WHERE ac.groupname = 'Direkte omkostninger'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1C: IGNORE — accounts in the 'Igangværende arbejde' category
-- Work-in-progress / balance sheet entries. Excluded from all P&L views.
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'IGNORE'
WHERE ac.groupname = 'Igangværende arbejde'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1D: OPEX — 'Delte services'
-- Shared services / HR admin costs (e.g., recruiting, training, benefits).
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'OPEX'
WHERE ac.groupname = 'Delte services'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1E: OPEX — 'Variable omkostninger'
-- Variable operating costs (IT tools, transport, subscriptions).
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'OPEX'
WHERE ac.groupname = 'Variable omkostninger'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1F: OPEX — 'Lokaleomkostninger'
-- Premises / facility costs (rent, utilities, cleaning).
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'OPEX'
WHERE ac.groupname = 'Lokaleomkostninger'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1G: OPEX — 'Salgsfremmende omkostninger'
-- Sales and marketing costs (events, campaigns, entertainment).
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'OPEX'
WHERE ac.groupname = 'Salgsfremmende omkostninger'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 1H: OPEX — 'Øvrige administrationsomk. i alt'
-- Other administrative overhead (legal, audit, insurance, bank charges).
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts aa
    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
SET aa.cost_type = 'OPEX'
WHERE ac.groupname = 'Øvrige administrationsomk. i alt'
  AND aa.cost_type  = 'OTHER';

-- ---------------------------------------------------------------------------
-- STEP 2: SALARIES fallback — salary flag for accounts still unclassified
-- Only applies to accounts whose groupname did not match any category above.
-- Salary accounts are distributed with salary pool capping
-- (staff base × 1.02 buffer) by IntercompanyCalcService.
-- ---------------------------------------------------------------------------
UPDATE accounting_accounts
SET cost_type = 'SALARIES'
WHERE salary    = 1
  AND cost_type = 'OTHER';

-- ---------------------------------------------------------------------------
-- Verification queries (run after migration to confirm correct distribution)
-- ---------------------------------------------------------------------------
-- SELECT cost_type, COUNT(*) AS account_count
-- FROM accounting_accounts
-- GROUP BY cost_type
-- ORDER BY account_count DESC;
--
-- Expected approximate distribution (290 accounts total):
--   OPEX         : majority of non-salary operating accounts
--   REVENUE      : Varesalg accounts
--   DIRECT_COSTS : Direkte omkostninger accounts
--   SALARIES     : salary=1 accounts not covered by above groupnames
--   IGNORE       : Igangværende arbejde accounts
--   OTHER        : residual; should be small (balance sheet etc.)
--
-- Confirm no DIRECT_COSTS accounts are also shared=true (per spec):
-- SELECT COUNT(*) FROM accounting_accounts
-- WHERE cost_type = 'DIRECT_COSTS' AND shared = 1;
-- Expected: 0
--
-- Confirm salary accounts mapped correctly:
-- SELECT aa.account_code, aa.account_description, aa.salary, aa.cost_type,
--        ac.groupname
-- FROM accounting_accounts aa
-- LEFT JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid
-- WHERE aa.salary = 1
-- ORDER BY aa.companyuuid, aa.account_code;
