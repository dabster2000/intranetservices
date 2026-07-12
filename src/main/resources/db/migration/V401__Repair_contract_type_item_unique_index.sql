-- The nightly prod-to-staging refresh recreates application tables from the
-- production schema but deliberately preserves staging Flyway history. Until
-- V400 reaches production, that can remove this index while leaving V400 marked
-- successful. Reassert it idempotently; after production promotion the V400
-- index is copied by the refresh and this migration is a no-op.
CREATE UNIQUE INDEX IF NOT EXISTS uq_contract_type_items_contract_key
    ON contract_type_items (contractuuid, name);
