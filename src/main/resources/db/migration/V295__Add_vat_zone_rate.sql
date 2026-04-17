-- V295__Add_vat_zone_rate.sql
--
-- Adds the local VAT rate percent to vat_zone_mapping. Trustworks-side UI uses
-- this for pre-finalization totals; e-conomic continues to apply its own VAT
-- from economics_vat_zone_number on booking.
--
-- DKK rows default to 25 (Denmark). All other rows default to 0 (EU reverse
-- charge / export). Admin can edit via Settings → E-conomics → VAT Zones.

ALTER TABLE vat_zone_mapping
    ADD COLUMN vat_rate_percent DECIMAL(5,2) NOT NULL DEFAULT 0.00;

UPDATE vat_zone_mapping
SET vat_rate_percent = 25.00
WHERE currency = 'DKK';
