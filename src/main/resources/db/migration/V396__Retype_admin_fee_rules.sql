-- V396: Retype ADMIN_FEE_PERCENT rows to PERCENT_DISCOUNT_ON_SUM + purpose='ADMIN_FEE',
-- and tag pre-existing PERCENT_DISCOUNT_ON_SUM rows with purpose='DISCOUNT'
-- (Framework Agreements Phase 3, spec §8.2 step V_b).
--
-- Output-neutral (numbers): the engine computes the identical delta for both types,
-- delta = -(base * pct / 100) at scale 2 HALF_UP:
--   PricingEngine.java:58-62  ADMIN_FEE_PERCENT       -> pct = rule.percent (else 0)
--   PricingEngine.java:53-57  PERCENT_DISCOUNT_ON_SUM -> pct = resolvePercent
--                             (PricingEngine.java:136-143: param_key lookup -> rule.percent -> 0)
-- Every ADMIN_FEE_PERCENT row in prod (ids 2, 6, 8: ski21721-admin 2%, ski21725-admin 4%,
-- ski21525-admin 4%) has percent set and param_key NULL, so resolvePercent returns
-- rule.percent and all deltas, cumulative sums, the >=0 clamp and VAT are unchanged.
-- Output-neutral (labels): the engine is purpose-aware for label formatting —
-- PERCENT_DISCOUNT_ON_SUM rows with purpose='ADMIN_FEE' render their stored label
-- VERBATIM (no " (<pct>%)" suffix), exactly like the legacy ADMIN_FEE_PERCENT branch,
-- so breakdown lines and persisted synthetic invoice items stay byte-identical across
-- this retype (PricingEngine PERCENT_DISCOUNT_ON_SUM case; gated by
-- PricingParitySnapshotTest.retype_v396_output_including_labels_is_byte_identical).
--
-- DELIBERATE: prod row id=10 (NOVO_MSP_2025 / msp-fee, 1.8%) is tagged 'DISCOUNT' by the
-- blanket second statement although it is semantically an admin fee. Retagging it
-- 'ADMIN_FEE' here would change its rendered invoice label ("MSP fee (1.8%)" -> "MSP fee")
-- and break the §12.2 byte-identity gate; if the tag matters for display it can be
-- retagged later via the UI as a conscious, audited edit.
--
-- Canary-safe (ECS Express): the retype writes 'PERCENT_DISCOUNT_ON_SUM', an enum value
-- every deployed version of the code already parses and executes; the purpose column was
-- added additively in V395 and is invisible to the old task's Hibernate metadata.
-- Deploy AFTER V395 is live in prod and old tasks are drained (spec §8.2 two-step rule).
--
-- Idempotent: re-running matches 0 rows (no ADMIN_FEE_PERCENT rows remain; every
-- PERCENT_DISCOUNT_ON_SUM row then has a non-NULL purpose). 'updated_at = updated_at'
-- suppresses the ON UPDATE CURRENT_TIMESTAMP trigger so row timestamps stay unchanged.
-- Statement order matters: the retype runs first so retyped fee rows are never
-- mis-tagged 'DISCOUNT' by the second statement.

UPDATE pricing_rule_steps
   SET rule_step_type = 'PERCENT_DISCOUNT_ON_SUM',
       purpose        = 'ADMIN_FEE',
       updated_at     = updated_at
 WHERE rule_step_type = 'ADMIN_FEE_PERCENT';

UPDATE pricing_rule_steps
   SET purpose    = 'DISCOUNT',
       updated_at = updated_at
 WHERE rule_step_type = 'PERCENT_DISCOUNT_ON_SUM'
   AND purpose IS NULL;
