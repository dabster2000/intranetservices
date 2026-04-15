-- =============================================================================
-- Migration V286: E-conomics invoice API migration — schema foundation
-- =============================================================================
-- Spec: docs/specs/economics-invoice-api-migration-spec.md (Phase A, §5.x)
--
-- Purpose: Additive schema changes that prepare the database for the new
--          e-conomics invoice flow. No runtime behavior changes — all new
--          columns are nullable (or default-valued) and all new tables are
--          empty until later phases populate them.
--
-- Sections:
--   1. client            — add type, default_billing_attention, default_billing_email
--   2. client_economics_customer    (new table)
--   3. client_economics_contacts    (new table)
--   4. payment_terms_mapping        (new table)
--   5. vat_zone_mapping             (new table)
--   6. contracts         — add billing_client_uuid, billing_attention,
--                          billing_email, billing_ref, payment_terms_uuid
--   7. invoices          — add billing_client_uuid, economics_draft_number,
--                          economics_booked_number; PENDING_REVIEW handled
--                          at JPA layer (status is VARCHAR, no enum check)
--   8. invoice_economics_uploads — add economics_draft_number,
--                          economics_booked_number (status column stays
--                          VARCHAR; new values handled at application layer)
--
-- Backward compatibility: Fully backward compatible. No existing columns are
-- removed or renamed. All FKs are nullable. Existing INSERTs continue to work.
-- =============================================================================

-- 1. client: type + default billing contact fields
ALTER TABLE client
    ADD COLUMN type                       VARCHAR(10)  NOT NULL DEFAULT 'CLIENT',
    ADD COLUMN default_billing_attention  VARCHAR(150) NULL,
    ADD COLUMN default_billing_email      VARCHAR(255) NULL;

CREATE INDEX idx_client_type ON client (type);

-- 2. client_economics_customer
CREATE TABLE client_economics_customer (
    uuid             VARCHAR(36)  NOT NULL,
    client_uuid      VARCHAR(36)  NOT NULL,
    company_uuid     VARCHAR(36)  NOT NULL,
    customer_number  INT          NOT NULL,
    object_version   VARCHAR(36)  NULL,
    pairing_source   VARCHAR(20)  NOT NULL,
    synced_at        DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_client_economics_customer_client_company (client_uuid, company_uuid),
    CONSTRAINT fk_client_economics_customer_client  FOREIGN KEY (client_uuid)  REFERENCES client(uuid),
    CONSTRAINT fk_client_economics_customer_company FOREIGN KEY (company_uuid) REFERENCES companies(uuid)
);

-- 3. client_economics_contacts
CREATE TABLE client_economics_contacts (
    uuid                     VARCHAR(36)  NOT NULL,
    client_uuid              VARCHAR(36)  NOT NULL,
    company_uuid             VARCHAR(36)  NOT NULL,
    contact_name             VARCHAR(150) NOT NULL,
    customer_contact_number  INT          NOT NULL,
    object_version           VARCHAR(36)  NULL,
    receive_einvoices        BOOLEAN      NOT NULL DEFAULT FALSE,
    einvoice_id              VARCHAR(50)  NULL,
    synced_at                DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_client_economics_contacts_client_company_name (client_uuid, company_uuid, contact_name),
    CONSTRAINT fk_client_economics_contacts_client  FOREIGN KEY (client_uuid)  REFERENCES client(uuid),
    CONSTRAINT fk_client_economics_contacts_company FOREIGN KEY (company_uuid) REFERENCES companies(uuid)
);

-- 4. payment_terms_mapping
CREATE TABLE payment_terms_mapping (
    uuid                              VARCHAR(36)  NOT NULL,
    payment_terms_type                VARCHAR(30)  NOT NULL,
    payment_days                      INT          NULL,
    company_uuid                      VARCHAR(36)  NULL,
    economics_payment_terms_number    INT          NOT NULL,
    economics_payment_terms_name      VARCHAR(50)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_payment_terms_mapping_type_days_company (payment_terms_type, payment_days, company_uuid),
    CONSTRAINT fk_payment_terms_mapping_company FOREIGN KEY (company_uuid) REFERENCES companies(uuid)
);

-- 5. vat_zone_mapping
CREATE TABLE vat_zone_mapping (
    uuid                       VARCHAR(36)  NOT NULL,
    currency                   VARCHAR(10)  NOT NULL,
    company_uuid               VARCHAR(36)  NULL,
    economics_vat_zone_number  INT          NOT NULL,
    economics_vat_zone_name    VARCHAR(50)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_vat_zone_mapping_currency_company (currency, company_uuid),
    CONSTRAINT fk_vat_zone_mapping_company FOREIGN KEY (company_uuid) REFERENCES companies(uuid)
);

-- 6. contracts: billing entity + payment terms
ALTER TABLE contracts
    ADD COLUMN billing_client_uuid  VARCHAR(36)  NULL,
    ADD COLUMN billing_attention    VARCHAR(150) NULL,
    ADD COLUMN billing_email        VARCHAR(255) NULL,
    ADD COLUMN billing_ref          VARCHAR(500) NULL,
    ADD COLUMN payment_terms_uuid   VARCHAR(36)  NULL,
    ADD CONSTRAINT fk_contracts_billing_client FOREIGN KEY (billing_client_uuid) REFERENCES client(uuid),
    ADD CONSTRAINT fk_contracts_payment_terms  FOREIGN KEY (payment_terms_uuid)  REFERENCES payment_terms_mapping(uuid);

CREATE INDEX idx_contracts_billing_client ON contracts (billing_client_uuid);

-- 7. invoices: billing entity snapshot + e-conomics numbers
ALTER TABLE invoices
    ADD COLUMN billing_client_uuid       VARCHAR(36) NULL,
    ADD COLUMN economics_draft_number    INT         NULL,
    ADD COLUMN economics_booked_number   INT         NULL,
    ADD CONSTRAINT fk_invoices_billing_client FOREIGN KEY (billing_client_uuid) REFERENCES client(uuid);

CREATE INDEX idx_invoices_billing_client       ON invoices (billing_client_uuid);
CREATE INDEX idx_invoices_economics_draft      ON invoices (economics_draft_number);
CREATE INDEX idx_invoices_economics_booked     ON invoices (economics_booked_number);

-- 8. invoice_economics_uploads: track new draft/book lifecycle
-- The status column is currently VARCHAR — we keep VARCHAR (no enum change required).
-- New status values DRAFT_CREATED, BOOK_PENDING, BOOKED are introduced at the
-- application layer; existing values PENDING / SUCCESS / FAILED keep working.
ALTER TABLE invoice_economics_uploads
    ADD COLUMN economics_draft_number   INT NULL,
    ADD COLUMN economics_booked_number  INT NULL;
