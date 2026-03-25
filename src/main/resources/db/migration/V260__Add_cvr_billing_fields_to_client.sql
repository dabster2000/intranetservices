-- =============================================================================
-- Migration V259: Add CVR and billing fields to client table
-- =============================================================================
-- Purpose: Adds 13 columns to the `client` table for CVR (Danish company
--          registry) data and billing address information. This prepares the
--          client entity for:
--          1. CVR API integration (cvrapi.dk auto-lookup)
--          2. Duplicate detection (find-or-create by CVR)
--          3. E-conomics invoice sync (SPEC-INV-001, future)
--
-- Columns added:
--   cvr              - Danish CVR number (8 digits for DK companies, optional
--                      for non-DK). No UNIQUE constraint: same CVR may appear
--                      on parent/subsidiary client records.
--   ean              - EAN/GLN for electronic invoicing to Danish public sector.
--   billing_address  - Street name and number. VARCHAR(510) matches e-conomics
--                      customer.address max length.
--   billing_zipcode  - Postal code. VARCHAR (not INT) to support international
--                      formats (e.g., UK "SW1A 1AA").
--   billing_city     - City name.
--   billing_country  - ISO 3166-1 alpha-2 country code. Defaults to 'DK'
--                      because all existing clients are Danish.
--   billing_email    - Email for invoice delivery.
--   currency         - ISO 4217 currency code. Defaults to 'DKK'.
--   phone            - Business phone from CVR registry.
--   industry_code    - Danish branchekode from CVR registry (int).
--   industry_desc    - Industry description from CVR registry.
--   company_code     - Danish legal form code (10=A/S, 80=ApS, etc.)
--   company_desc     - Legal form description (e.g., "Anpartsselskab").
--
-- Nullability: All new columns are nullable except billing_country and
--              currency which have NOT NULL with defaults. This ensures
--              backward compatibility -- existing rows get sensible defaults,
--              and existing INSERT/UPDATE statements that omit these columns
--              continue to work.
--
-- Backward compatibility: Fully backward compatible. No existing columns are
--   changed, removed, or renamed. No constraints are added to existing columns.
--   Existing CRUD operations on the client table are unaffected.
--
-- Indexes: An index on cvr is added for efficient duplicate detection and
--   search by CVR number. Not unique (see design note above).
--
-- Rollback:
--   ALTER TABLE client
--       DROP INDEX idx_client_cvr,
--       DROP COLUMN cvr,
--       DROP COLUMN ean,
--       DROP COLUMN billing_address,
--       DROP COLUMN billing_zipcode,
--       DROP COLUMN billing_city,
--       DROP COLUMN billing_country,
--       DROP COLUMN billing_email,
--       DROP COLUMN currency,
--       DROP COLUMN phone,
--       DROP COLUMN industry_code,
--       DROP COLUMN industry_desc,
--       DROP COLUMN company_code,
--       DROP COLUMN company_desc;
--
-- Affected entities:
--   - dk.trustworks.intranet.dao.crm.model.Client (add 13 JPA fields)
--   - dk.trustworks.intranet.dao.crm.services.ClientService (extend updateOne SQL)
--   - dk.trustworks.intranet.apigateway.resources.ClientResource (validation, search)
-- =============================================================================

ALTER TABLE client
    -- Billing fields (needed for SPEC-INV-001 e-conomics sync)
    ADD COLUMN cvr              VARCHAR(20)   NULL,
    ADD COLUMN ean              VARCHAR(20)   NULL,
    ADD COLUMN billing_address  VARCHAR(510)  NULL,
    ADD COLUMN billing_zipcode  VARCHAR(30)   NULL,
    ADD COLUMN billing_city     VARCHAR(50)   NULL,
    ADD COLUMN billing_country  VARCHAR(2)    NOT NULL DEFAULT 'DK',
    ADD COLUMN billing_email    VARCHAR(255)  NULL,
    ADD COLUMN currency         VARCHAR(3)    NOT NULL DEFAULT 'DKK',
    -- CVR registry data (auto-populated from cvrapi.dk)
    ADD COLUMN phone            VARCHAR(50)   NULL,
    ADD COLUMN industry_code    INT           NULL,
    ADD COLUMN industry_desc    VARCHAR(255)  NULL,
    ADD COLUMN company_code     INT           NULL,
    ADD COLUMN company_desc     VARCHAR(100)  NULL;

-- Index for CVR lookup and duplicate detection (GET /clients/search?cvr=...)
CREATE INDEX idx_client_cvr ON client (cvr);
