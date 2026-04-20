-- =============================================================================
-- Migration V298: Backfill per-company rows for the NULL-company VAT zones
-- =============================================================================
-- Spec: docs/superpowers/specs/2026-04-20-economics-mappings-company-scoping-design.md
--
-- Both staging and production carry three "global" (company_uuid IS NULL) rows:
--   DKK → zone 1 Domestic 25%
--   EU  → zone 2 EU       0%
--   WOR → zone 3 Abroad   0%
-- Finalization falls back to these when no per-company row exists.
--
-- Verified via e-conomic REST: all three Trustworks agreements (A/S,
-- Technology, Cyber Security) have identical zone numbers 1/2/3 with the
-- same names. Zones 1/2/3 are e-conomic's default Danish accounting zones.
--
-- This migration replicates each global row into a per-company row for every
-- configured Trustworks company, then deletes the globals. Behaviour
-- post-migration is identical to pre-migration, except the lookup path is
-- the explicit `(currency, company_uuid)` row instead of the fallback.
--
-- Idempotent: ON DUPLICATE KEY UPDATE is a no-op, safe to re-run.
-- =============================================================================

INSERT INTO vat_zone_mapping
    (uuid, currency, company_uuid, economics_vat_zone_number,
     economics_vat_zone_name, vat_rate_percent)
SELECT UUID(),
       v.currency,
       c.uuid,
       v.economics_vat_zone_number,
       v.economics_vat_zone_name,
       v.vat_rate_percent
FROM vat_zone_mapping v
CROSS JOIN companies c
WHERE v.company_uuid IS NULL
ON DUPLICATE KEY UPDATE
    vat_zone_mapping.uuid = vat_zone_mapping.uuid;

DELETE FROM vat_zone_mapping WHERE company_uuid IS NULL;
