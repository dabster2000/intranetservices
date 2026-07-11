-- V397: Ensure the legacy hardcoded PricingRuleCatalog rule sets exist in pricing_rule_steps.
--
-- Spec: docs/superpowers/specs/2026-07-10-framework-agreements-redesign-design.md §9.7
-- ("Legacy hardcoded rules → DB permanence").
--
-- WHY
--   Until this migration, PricingRuleCatalog carried an in-code copy of the SKI rule sets
--   and silently fell back to it whenever pricing_rule_steps had ZERO rows that were both
--   active=1 AND date-valid for the invoice date. After this migration ships, the hardcoded
--   fallback is deleted from PricingRuleCatalog and the database is the only source of
--   pricing rules (the priority-9000 invoice-discount injection in code remains — it is by
--   design and visible in the UI).
--
-- WHAT
--   Idempotent re-insert of the 9 hardcoded rules, guarded on (contract_type_code, rule_id).
--   Values are byte-faithful to the hardcoded definitions in PricingRuleCatalog (which V98
--   seeded verbatim): same rule_step_type, step_base, percent/amount, param_key, priority,
--   active=1, and no validity dates.
--
--   Verified against the V98 seed and the 2026-07-10 production extract: all 9
--   (contract_type_code, rule_id) pairs already exist in production (pricing_rule_steps
--   ids 1-9), so this migration is expected to be a NO-OP everywhere V98 ran. Production
--   edits to those rows (SKI0215_2025 admin fee got valid_from/valid_to, two "Generel
--   rabat" rows were deactivated) are intentionally preserved — the guard never updates
--   existing rows.
--
--   Each insert additionally requires the parent contract_type_definitions row to exist
--   (FK fk_pricing_rule_contract_type is ON DELETE CASCADE): if an agreement was
--   deliberately deleted, its rules must stay gone.
--
--   Note on types: inserts use the original ADMIN_FEE_PERCENT type verbatim. V396 retypes
--   pre-existing ADMIN_FEE_PERCENT rows to PERCENT_DISCOUNT_ON_SUM + purpose=ADMIN_FEE and
--   runs BEFORE this migration; in the expected no-op case nothing new is inserted, and in
--   the theoretical gap-fill case the Java enum keeps ADMIN_FEE_PERCENT (@Deprecated) and
--   the engine math is identical for both types.
--
-- BEHAVIOR CHANGE (documented for the parity gate, spec §12.2)
--   With the hardcoded fallback deleted, "zero active-on-date rules" honestly means "no
--   deductions" (only the invoice-discount fallback runs). Windows where the old fallback
--   actually fired, per the 2026-07-10 production extract:
--     * SKI0215_2025 — its only active rule (ski21525-admin, 4%) is date-bounded
--       [2025-07-01, 2026-01-01) and ski21525-general is inactive. For invoice dates
--       >= 2026-01-01 (i.e. all current invoices) or < 2025-07-01 the DB yields zero rules,
--       and the OLD code resurrected the undated hardcoded set (4% admin fee + general
--       discount). NEW behavior: no 4% admin fee outside the configured window. This is
--       the intended "honest pricing" fix — the fee's expiry (set 2026-07-04 in prod)
--       now actually takes effect.
--     * SKI0217_2021 / SKI0217_2025 — all prod rows are undated and the deduction rules
--       are active, so the DB never yields zero rules today: NO behavior change unless
--       someone deactivates every rule, in which case deactivation now honestly means
--       "no deductions" instead of silently resurrecting the hardcoded set.
--
-- Rollback: DELETE FROM pricing_rule_steps WHERE <pair> for any row this migration
-- inserted (identifiable via created_at = migration time); no-op case needs no rollback.

-- ── SKI0217_2021: trapperabat -> 2% admin -> 2000 fixed fee -> generel rabat ──────────

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2021', 'ski21721-key', 'SKI trapperabat', 'PERCENT_DISCOUNT_ON_SUM', 'SUM_BEFORE_DISCOUNTS',
       NULL, NULL, 'trapperabat', NULL, NULL, 10, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2021')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2021' AND p.rule_id = 'ski21721-key');

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2021', 'ski21721-admin', '2% SKI administrationsgebyr', 'ADMIN_FEE_PERCENT', 'CURRENT_SUM',
       2.0, NULL, NULL, NULL, NULL, 20, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2021')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2021' AND p.rule_id = 'ski21721-admin');

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2021', 'ski21721-fee', 'Faktureringsgebyr', 'FIXED_DEDUCTION', 'CURRENT_SUM',
       NULL, 2000.00, NULL, NULL, NULL, 30, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2021')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2021' AND p.rule_id = 'ski21721-fee');

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2021', 'ski21721-general', 'Generel rabat', 'GENERAL_DISCOUNT_PERCENT', 'CURRENT_SUM',
       NULL, NULL, NULL, NULL, NULL, 40, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2021')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2021' AND p.rule_id = 'ski21721-general');

-- ── SKI0217_2025: trapperabat -> 4% admin -> generel rabat ────────────────────────────

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2025', 'ski21725-key', 'SKI trapperabat', 'PERCENT_DISCOUNT_ON_SUM', 'SUM_BEFORE_DISCOUNTS',
       NULL, NULL, 'trapperabat', NULL, NULL, 10, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2025')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2025' AND p.rule_id = 'ski21725-key');

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2025', 'ski21725-admin', '4% SKI administrationsgebyr', 'ADMIN_FEE_PERCENT', 'CURRENT_SUM',
       4.0, NULL, NULL, NULL, NULL, 20, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2025')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2025' AND p.rule_id = 'ski21725-admin');

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0217_2025', 'ski21725-general', 'Generel rabat', 'GENERAL_DISCOUNT_PERCENT', 'CURRENT_SUM',
       NULL, NULL, NULL, NULL, NULL, 40, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0217_2025')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0217_2025' AND p.rule_id = 'ski21725-general');

-- ── SKI0215_2025: 4% admin -> generel rabat ───────────────────────────────────────────

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0215_2025', 'ski21525-admin', '4% SKI administrationsgebyr', 'ADMIN_FEE_PERCENT', 'CURRENT_SUM',
       4.0, NULL, NULL, NULL, NULL, 20, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0215_2025')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0215_2025' AND p.rule_id = 'ski21525-admin');

INSERT INTO pricing_rule_steps
    (contract_type_code, rule_id, label, rule_step_type, step_base,
     percent, amount, param_key, valid_from, valid_to, priority, active)
SELECT 'SKI0215_2025', 'ski21525-general', 'Generel rabat', 'GENERAL_DISCOUNT_PERCENT', 'CURRENT_SUM',
       NULL, NULL, NULL, NULL, NULL, 40, TRUE
FROM DUAL
WHERE EXISTS (SELECT 1 FROM contract_type_definitions d WHERE d.code = 'SKI0215_2025')
  AND NOT EXISTS (SELECT 1 FROM pricing_rule_steps p
                  WHERE p.contract_type_code = 'SKI0215_2025' AND p.rule_id = 'ski21525-general');
