-- =============================================================================
-- Migration V317: Drop legacy contracts.refid column
-- =============================================================================
-- Spec: docs/superpowers/specs/2026-05-05-invoice-refs-redesign-design.md §4.
--
-- Pre-conditions (all guaranteed by prior migrations + code changes):
--   1. V288 copied every non-null refid into billing_ref (97% identity).
--   2. V316 backfilled Invoice.contractref from contract.billing_ref where
--      missing — every invoice has its snapshot.
--   3. Java code no longer references Contract.refid (entity field removed).
--
-- Rollback: if absolutely needed post-merge, the column can be re-added and
-- repopulated from billing_ref. The original distinction between refid and
-- billing_ref was already lost by V288, so a rollback only re-creates the
-- redundancy — no unique data is at risk.
-- =============================================================================

ALTER TABLE contracts DROP COLUMN refid;
