-- src/main/resources/db/migration/V20250908__pricing_engine_init.sql
ALTER TABLE invoiceitems
    ADD COLUMN IF NOT EXISTS origin VARCHAR(20) DEFAULT 'BASE',
    ADD COLUMN IF NOT EXISTS calculation_ref VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rule_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS label VARCHAR(255);

-- optional index hvis man vil kunne slå beregnede linjer op hurtigt
CREATE INDEX IF NOT EXISTS idx_invoiceitems_origin ON invoiceitems(origin);
