-- ======================================================================================
-- V399: Cleanup of audited contract-rule test/orphan data
-- ======================================================================================
-- Purpose: remove rows identified in the 2026-07-10 production data audit
--          (read-only prod extract, Phase 3 fixtures; spec §9.12). Every DELETE is
--          guarded to the EXACT audited row (id + distinguishing columns) so the
--          migration is a strict no-op on any database where the row differs or is
--          already gone (dev, staging, test snapshots).
-- Spec:    docs/superpowers/specs/2026-07-10-framework-agreements-redesign-design.md §9.12
--
-- NOT touched (deliberately, per §9.12): pricing_rule_steps id=4
-- (SKI0217_2021 / ski21721-general, active NULL-percent GENERAL_DISCOUNT_PERCENT) —
-- it is the system-managed invoice-discount placement step; the engine reads
-- invoice.discount, so the NULL percent is by design. The two inactive
-- GENERAL_DISCOUNT_PERCENT placeholders (ids 7, 9) stay archived as-is.
-- ======================================================================================

-- Manual test row created via the UI during testing (audit: rule_id='test', label='jiji').
DELETE FROM contract_validation_overrides
WHERE id = 4 AND rule_id = 'test' AND label = 'jiji';

-- Orphan agreement-parameter rows with no owning contract (audit: contractuuid IS NULL).
DELETE FROM contract_type_items
WHERE id = 9 AND contractuuid IS NULL AND name = 'trapperabat';

DELETE FROM contract_type_items
WHERE id = 10 AND contractuuid IS NULL AND name = 'trapperabat';

DELETE FROM contract_type_items
WHERE id = 20 AND contractuuid IS NULL AND name = 'trapperabat';

-- Parameter row with NULL value (audit: id=19) — a NULL trapperabat resolves to no
-- deduction and only produces noise in param-key resolution.
DELETE FROM contract_type_items
WHERE id = 19 AND value IS NULL AND name = 'trapperabat';
