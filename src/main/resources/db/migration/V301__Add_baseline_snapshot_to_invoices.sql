-- =============================================================================
-- Migration V301: Add baseline_snapshot column to invoices
--
-- Purpose:
--   Persist the draft's initial consultant-hour distribution at draft creation
--   so the DeltaAbsorptionEngine can compare later edits against a stable
--   reference state. Without this, baseline is derived from the `work` table,
--   which diverges from the draft's actual line items in many real cases
--   (different billing month, "helped by" consultants, manual edits).
--
--   Column is nullable: existing drafts without a snapshot fall back to the
--   work-table baseline (current behavior). New drafts written from here on
--   populate the snapshot at createDraftInvoice time.
--
-- Shape: {"consultantUuid": hours, ...}
-- =============================================================================

ALTER TABLE invoices
    ADD COLUMN baseline_snapshot JSON NULL
    COMMENT 'consultantUuid -> hours map captured at draft creation';
