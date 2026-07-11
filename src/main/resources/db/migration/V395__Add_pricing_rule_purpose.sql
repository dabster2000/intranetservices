-- V395: Add pricing_rule_steps.purpose (Framework Agreements Phase 3, spec §8.2 step V_a — additive only).
--
-- Purpose tags a PERCENT_DISCOUNT_ON_SUM rule with its business meaning:
--   'DISCOUNT'  — a genuine customer discount (trapperabat, commercial adjustment, ...)
--   'ADMIN_FEE' — a fee withheld by the framework owner (SKI administrationsgebyr, MSP fee, ...)
--   NULL        — system/placement rows (GENERAL_DISCOUNT_PERCENT, FIXED_DEDUCTION) and
--                 not-yet-tagged rows during rollout.
--
-- Output-neutral: the pricing engine never reads this column. The engine math for both
-- purposes is the same formula, delta = -(base * pct / 100):
--   PricingEngine.java:53-57  (PERCENT_DISCOUNT_ON_SUM)
--   PricingEngine.java:58-62  (ADMIN_FEE_PERCENT — identical delta expression)
-- The tag exists only for display (FE taxonomy: 'Percentage deduction · Discount | Admin fee')
-- and for the V396 retype that folds ADMIN_FEE_PERCENT into PERCENT_DISCOUNT_ON_SUM.
--
-- Canary-safe (ECS Express): purely additive nullable column. The old task's Hibernate
-- metadata does not know the column, so the draining task neither selects nor writes it;
-- inserts from old code leave it NULL, which the new code treats as "untagged" with an
-- ADMIN_FEE_PERCENT->ADMIN_FEE fallback in the DTO mapping.
--
-- Deploy sequencing (spec §8.2): promote this migration (and the code reading purpose)
-- to prod BEFORE relying on V396 — the staging ~02:00 prod->staging refresh strips
-- unpromoted migrations.
--
-- IF NOT EXISTS (MariaDB-native) makes the statement idempotent: prod never re-runs it,
-- but the shared local dev DB replays this range whenever flyway repair-at-start has
-- marked it deleted from a checkout without the file and out-of-order re-resolves it.

ALTER TABLE pricing_rule_steps
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(20) NULL
        COMMENT 'Business purpose for PERCENT_DISCOUNT_ON_SUM rules: DISCOUNT or ADMIN_FEE; NULL on system/placement rows';
