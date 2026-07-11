-- ======================================================================================
-- V398: Widen contract-type code columns to VARCHAR(50)
-- ======================================================================================
-- Purpose: contracts.contracttype is VARCHAR(15); the longest live agreement code,
--          SKI0215_2025_V2, is exactly 15 characters — the column sits at its limit,
--          so any longer contract type code (e.g. a future SKI vintage) fails to
--          persist. Widen to VARCHAR(50), matching
--          contract_type_definitions.code VARCHAR(50) (V96) and
--          pricing_rule_steps.contract_type_code VARCHAR(50) (V97).
--
--          invoices.contract_type (V27: VARCHAR(30) NOT NULL DEFAULT 'PERIOD') carries
--          the same codes onto every invoice at creation/finalization. Widening only
--          the contracts column would let a 31-50 char code persist as a definition and
--          on a contract, then fail with "Data too long" the first time an invoice is
--          created for it — so both columns are widened together (same concern: every
--          column that stores a contract-type code moves to VARCHAR(50)).
-- Spec:    docs/superpowers/specs/2026-07-10-framework-agreements-redesign-design.md §9.11
-- Safety:  additive-safe — widens only, keeps NOT NULL (and the invoices default),
--          no data change; the V99 CHECK constraints
--          (chk_contract_contracttype_format, chk_invoice_contract_type_format) and
--          indexes are unaffected.
-- ======================================================================================

ALTER TABLE contracts MODIFY contracttype VARCHAR(50) NOT NULL;

ALTER TABLE invoices MODIFY contract_type VARCHAR(50) NOT NULL DEFAULT 'PERIOD';
