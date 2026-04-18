-- =============================================================================
-- Migration V296: Defensive repair for V286 partial application
-- =============================================================================
-- Background:
--   On staging, V286 (Invoice api migration schema foundation) recorded as
--   success in flyway_schema_history but only sections 1-5 actually executed.
--   Sections 6-8 (ALTER contracts/invoices/invoice_economics_uploads) silently
--   did NOT apply, leaving the schema inconsistent with the entity model and
--   breaking GET /invoices/months and the draft invoice flow.
--
--   We could not isolate the original failure cause (DB charsets, FK targets,
--   and individual ALTER replays all succeed). To prevent the same situation
--   on production, this migration re-asserts the V286 schema additions using
--   idempotent patterns:
--     - ADD COLUMN IF NOT EXISTS
--     - CREATE INDEX IF NOT EXISTS
--     - Conditional FK creation via INFORMATION_SCHEMA check
--
-- Effect:
--   - Staging (already patched manually): no-op
--   - Production (clean V285 → runs V286 first): no-op if V286 succeeds fully,
--     safety net if V286 partially fails again.
-- =============================================================================

-- 6. contracts: billing entity + payment terms
ALTER TABLE contracts
    ADD COLUMN IF NOT EXISTS billing_client_uuid  VARCHAR(36)  NULL,
    ADD COLUMN IF NOT EXISTS billing_attention    VARCHAR(150) NULL,
    ADD COLUMN IF NOT EXISTS billing_email        VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS billing_ref          VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS payment_terms_uuid   VARCHAR(36)  NULL;

CREATE INDEX IF NOT EXISTS idx_contracts_billing_client ON contracts (billing_client_uuid);

-- 7. invoices: billing entity snapshot + e-conomics numbers
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS billing_client_uuid       VARCHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS economics_draft_number    INT         NULL,
    ADD COLUMN IF NOT EXISTS economics_booked_number   INT         NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_billing_client       ON invoices (billing_client_uuid);
CREATE INDEX IF NOT EXISTS idx_invoices_economics_draft      ON invoices (economics_draft_number);
CREATE INDEX IF NOT EXISTS idx_invoices_economics_booked     ON invoices (economics_booked_number);

-- 8. invoice_economics_uploads: track new draft/book lifecycle
ALTER TABLE invoice_economics_uploads
    ADD COLUMN IF NOT EXISTS economics_draft_number   INT NULL,
    ADD COLUMN IF NOT EXISTS economics_booked_number  INT NULL;

-- Foreign keys (MariaDB lacks IF NOT EXISTS for constraints — use conditional ADD)
-- fk_contracts_billing_client
SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.referential_constraints
    WHERE constraint_schema = DATABASE()
      AND constraint_name = 'fk_contracts_billing_client'
);
SET @sql := IF(@fk_exists = 0,
    'ALTER TABLE contracts ADD CONSTRAINT fk_contracts_billing_client FOREIGN KEY (billing_client_uuid) REFERENCES client(uuid)',
    'SELECT "fk_contracts_billing_client already exists" AS info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- fk_contracts_payment_terms
SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.referential_constraints
    WHERE constraint_schema = DATABASE()
      AND constraint_name = 'fk_contracts_payment_terms'
);
SET @sql := IF(@fk_exists = 0,
    'ALTER TABLE contracts ADD CONSTRAINT fk_contracts_payment_terms FOREIGN KEY (payment_terms_uuid) REFERENCES payment_terms_mapping(uuid)',
    'SELECT "fk_contracts_payment_terms already exists" AS info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- fk_invoices_billing_client
SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.referential_constraints
    WHERE constraint_schema = DATABASE()
      AND constraint_name = 'fk_invoices_billing_client'
);
SET @sql := IF(@fk_exists = 0,
    'ALTER TABLE invoices ADD CONSTRAINT fk_invoices_billing_client FOREIGN KEY (billing_client_uuid) REFERENCES client(uuid)',
    'SELECT "fk_invoices_billing_client already exists" AS info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
