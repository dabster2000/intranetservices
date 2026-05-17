-- ====================================================================
-- V351: Add extracted-receipt-facts columns to the expenses table.
--
-- Purpose: Support the AI Validation Console "Impact Preview" feature
-- (Phase 3). When the AI validation service runs OCR on a receipt, it
-- stores structured facts here so that threshold-rule simulations can
-- determine "would this rule have flipped?" without re-running OCR.
--
-- Columns added:
--   extracted_amount_dkk    DECIMAL(10,2) NULL
--       AI-extracted total from the receipt, converted to DKK.
--       NULL for older expenses where OCR has not (yet) run.
--
--   extracted_guest_count   INT NULL
--       AI-extracted guest count from the receipt text or meal notes.
--       NULL when no guest information was found on the receipt.
--
--   extracted_per_person_dkk DECIMAL(10,2) STORED GENERATED NULL
--       Derived: extracted_amount_dkk / extracted_guest_count, rounded
--       to 2 decimal places. NULL when either base column is NULL or
--       when extracted_guest_count = 0 (guard against divide-by-zero).
--       Used directly by the R_MEAL_COST_PER_PERSON rule evaluation in
--       the Impact Preview queries.
--
--   extracted_merchant_name VARCHAR(200) NULL
--       AI-extracted merchant / vendor name from the receipt header.
--       NULL when OCR did not identify a merchant.
--
-- Index added:
--   idx_expenses_extracted_per_person (extracted_per_person_dkk)
--       Supports range queries in Impact Preview: "which expenses would
--       flip if the per-person cap changes from X to Y?".
--
-- Backwards-compatibility guarantee:
--   All new columns are nullable. Existing rows remain unchanged; they
--   receive NULL for every new column. No backfill is performed —
--   impact preview gracefully degrades to "preview unavailable" for
--   expenses that pre-date OCR extraction.
--
-- Data semantics:
--   Monetary values: DECIMAL(10,2) — two decimal places, DKK, no
--   currency column (all values in this service are DKK).
--   Timezone: not applicable (no timestamp column added here).
--   Soft delete: not applicable (follows expenses table convention).
--
-- Phase wiring:
--   Phase 2 ExpenseDecisionsResource returns perPersonDkk: null today.
--   Phase 3 Task 3.6 (preview-impact) wires extracted_per_person_dkk
--   into the simulation query. Task 3.2 (ExpenseAIValidationService)
--   populates the three base columns on each validation run.
--
-- MariaDB version note:
--   STORED generated columns (vs PERSISTENT) require MariaDB >= 10.5.
--   This project targets MariaDB 10.x; STORED is used.
-- ====================================================================

ALTER TABLE expenses
  ADD COLUMN extracted_amount_dkk DECIMAL(10,2) NULL
    COMMENT 'AI-extracted total from receipt, in DKK; NULL if OCR has not run',
  ADD COLUMN extracted_guest_count INT NULL
    COMMENT 'AI-extracted guest count from receipt or text; NULL if not found',
  ADD COLUMN extracted_per_person_dkk DECIMAL(10,2) AS (
    CASE WHEN extracted_guest_count > 0
         THEN ROUND(extracted_amount_dkk / extracted_guest_count, 2)
         ELSE NULL END
  ) STORED
    COMMENT 'Computed: per-person amount (DKK) for meal-cap rule evaluation; NULL when base columns are NULL or guest_count = 0',
  ADD COLUMN extracted_merchant_name VARCHAR(200) NULL
    COMMENT 'AI-extracted merchant name from receipt header; NULL if not identified',
  ADD INDEX idx_expenses_extracted_per_person (extracted_per_person_dkk);
