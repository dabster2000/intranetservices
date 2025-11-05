-- ============================================================================
-- Flyway Migration V109: Invoice Status Domain Refactoring
-- ============================================================================
-- Purpose: Separate conflated invoice status concerns into distinct fields:
--   - type: INVOICE | CREDIT_NOTE | PHANTOM | INTERNAL | INTERNAL_SERVICE
--   - lifecycle_status: DRAFT | CREATED | SUBMITTED | PAID | CANCELLED
--   - finance_status: NONE | UPLOADED | BOOKED | PAID | ERROR
--   - processing_state: IDLE | QUEUED (operational flag)
--
-- Strategy: Side-by-side migration with backward compatibility view
-- Rollback: DROP TABLE invoices_v2; DROP TABLE invoice_number_sequences; DROP PROCEDURE next_invoice_number; DROP VIEW invoices_legacy;
-- ============================================================================

START TRANSACTION;

-- ============================================================================
-- 1. CREATE invoices_v2 TABLE
-- ============================================================================

CREATE TABLE invoices_v2 (
  uuid                CHAR(36) NOT NULL,
  issuer_companyuuid  CHAR(36) NOT NULL,
  debtor_companyuuid  CHAR(36) NULL,
  invoicenumber       INT NULL,
  invoice_series      VARCHAR(20) NULL,

  -- Separated status dimensions
  type                ENUM('INVOICE','CREDIT_NOTE','PHANTOM','INTERNAL','INTERNAL_SERVICE') NOT NULL,
  lifecycle_status    ENUM('DRAFT','CREATED','SUBMITTED','PAID','CANCELLED') NOT NULL DEFAULT 'DRAFT',
  finance_status      ENUM('NONE','UPLOADED','BOOKED','PAID','ERROR') NOT NULL DEFAULT 'NONE',
  processing_state    ENUM('IDLE','QUEUED') NOT NULL DEFAULT 'IDLE',
  queue_reason        ENUM('AWAIT_SOURCE_PAID','MANUAL_REVIEW','EXPORT_BATCH') NULL,

  -- Dates
  invoicedate         DATE NULL,
  duedate             DATE NULL,
  bookingdate         DATE NULL,

  -- Financial config
  currency            CHAR(3) NOT NULL DEFAULT 'DKK',
  vat_pct             DECIMAL(5,2) NOT NULL DEFAULT 25.00,
  header_discount_pct DECIMAL(7,4) NOT NULL DEFAULT 0.0000,

  -- References
  contractuuid        CHAR(36) NULL,
  projectuuid         CHAR(36) NULL,
  source_invoice_uuid CHAR(36) NULL,
  creditnote_for_uuid CHAR(36) NULL,

  -- Bill-to snapshot
  bill_to_name        VARCHAR(150) NULL,
  bill_to_attn        VARCHAR(150) NULL,
  bill_to_line1       VARCHAR(200) NULL,
  bill_to_line2       VARCHAR(150) NULL,
  bill_to_zip         VARCHAR(20)  NULL,
  bill_to_city        VARCHAR(100) NULL,
  bill_to_country     CHAR(2) NULL,
  bill_to_ean         VARCHAR(40)  NULL,
  bill_to_cvr         VARCHAR(40)  NULL,

  -- ERP integration
  economics_voucher_number INT NOT NULL DEFAULT 0,
  pdf_url             VARCHAR(500) NULL,
  pdf_sha256          CHAR(64) NULL,

  -- Computed columns for reporting
  invoice_year SMALLINT AS (YEAR(invoicedate)) PERSISTENT,
  invoice_month TINYINT AS (MONTH(invoicedate)) PERSISTENT,

  -- Audit timestamps
  created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  -- Constraints
  PRIMARY KEY (uuid),
  UNIQUE KEY uk_invoice_per_company_number (issuer_companyuuid, invoicenumber),
  KEY idx_invoices_type_status (type, lifecycle_status, finance_status),
  KEY idx_invoices_company_date (issuer_companyuuid, invoicedate),
  KEY idx_invoices_creditnote (creditnote_for_uuid),
  KEY idx_invoices_project (projectuuid),
  KEY idx_invoices_year_month_project (invoice_year, invoice_month, projectuuid)

-- CONSTRAINT fk_creditnote_ref FOREIGN KEY (creditnote_for_uuid) REFERENCES invoices_v2(uuid),
-- CONSTRAINT fk_source_invoice FOREIGN KEY (source_invoice_uuid)  REFERENCES invoices_v2(uuid),

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ============================================================================
-- 2. CREATE invoice_number_sequences TABLE
-- ============================================================================

CREATE TABLE invoice_number_sequences (
  issuer_companyuuid CHAR(36) NOT NULL,
  invoice_series     VARCHAR(20) NOT NULL DEFAULT '',
  last_number        INT NOT NULL DEFAULT 0,
  PRIMARY KEY (issuer_companyuuid, invoice_series)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ============================================================================
-- 3. CREATE next_invoice_number STORED PROCEDURE
-- ============================================================================

DELIMITER //
CREATE PROCEDURE next_invoice_number(
  IN p_issuer CHAR(36), 
  IN p_series VARCHAR(20), 
  OUT p_next INT
)
BEGIN
  INSERT INTO invoice_number_sequences(issuer_companyuuid, invoice_series, last_number)
  VALUES (p_issuer, IFNULL(p_series,''), 0)
  ON DUPLICATE KEY UPDATE last_number = LAST_INSERT_ID(last_number + 1);
  
  SET p_next = LAST_INSERT_ID();
END//
DELIMITER ;

-- ============================================================================
-- 4. DATA BACKFILL: invoices → invoices_v2
-- ============================================================================

INSERT INTO invoices_v2 (
  uuid, issuer_companyuuid, debtor_companyuuid,
  invoicenumber, invoice_series,
  type, lifecycle_status, finance_status, processing_state, queue_reason,
  invoicedate, duedate, bookingdate,
  currency, vat_pct, header_discount_pct,
  contractuuid, projectuuid, source_invoice_uuid, creditnote_for_uuid,
  bill_to_name, bill_to_attn, bill_to_line1, bill_to_line2, bill_to_zip, bill_to_city, bill_to_country, bill_to_ean, bill_to_cvr,
  economics_voucher_number, pdf_url, pdf_sha256
)
SELECT
  i.uuid,
  i.companyuuid AS issuer_companyuuid,
  i.debtor_companyuuid,
  NULLIF(i.invoicenumber, 0) AS invoicenumber,
  NULL AS invoice_series,

  -- TYPE MAPPING: Unify credit note identification
  CASE
    WHEN i.creditnote_for_uuid IS NOT NULL THEN 'CREDIT_NOTE'
    WHEN i.type = 'CREDIT_NOTE' THEN 'CREDIT_NOTE'
    WHEN i.status = 'CREDIT_NOTE' THEN 'CREDIT_NOTE'
    WHEN i.type IN ('INTERNAL','INTERNAL_SERVICE') THEN i.type
    WHEN i.type = 'PHANTOM' THEN 'PHANTOM'
    ELSE 'INVOICE'
  END AS type,

  -- LIFECYCLE STATUS MAPPING
  CASE
    WHEN i.status = 'DRAFT' THEN 'DRAFT'
    WHEN i.status = 'SUBMITTED' THEN 'SUBMITTED'
    WHEN i.status = 'PAID' THEN 'PAID'
    WHEN i.status IN ('CANCELLED','VOIDED') THEN 'CANCELLED'
    -- QUEUED with no number stays DRAFT (finalization pending)
    WHEN i.status = 'QUEUED' AND (i.invoicenumber IS NULL OR i.invoicenumber = 0) THEN 'DRAFT'
    -- CREDIT_NOTE status becomes type, lifecycle depends on presence of number
    WHEN i.status = 'CREDIT_NOTE' AND (i.invoicenumber IS NULL OR i.invoicenumber = 0) THEN 'DRAFT'
    WHEN i.status = 'CREDIT_NOTE' AND i.invoicenumber > 0 THEN 'CREATED'
    -- Default: has number → CREATED
    WHEN i.invoicenumber > 0 THEN 'CREATED'
    ELSE 'DRAFT'
  END AS lifecycle_status,

  -- FINANCE STATUS MAPPING (from economics_status)
  CASE
    WHEN i.economics_status IN ('NA','PENDING') OR i.economics_status IS NULL THEN 'NONE'
    WHEN i.economics_status IN ('UPLOADED','PARTIALLY_UPLOADED') THEN 'UPLOADED'
    WHEN i.economics_status = 'BOOKED' THEN 'BOOKED'
    WHEN i.economics_status = 'PAID' THEN 'PAID'
    ELSE 'NONE'
  END AS finance_status,

  -- PROCESSING STATE (operational flag)
  CASE 
    WHEN i.status = 'QUEUED' THEN 'QUEUED' 
    ELSE 'IDLE' 
  END AS processing_state,

  -- QUEUE REASON (only when queued)
  CASE 
    WHEN i.status = 'QUEUED' THEN 'AWAIT_SOURCE_PAID'
    ELSE NULL 
  END AS queue_reason,

  -- Dates
  i.invoicedate, 
  i.duedate, 
  i.bookingdate,

  -- Financial config (type conversions)
  i.currency,
  CAST(i.vat AS DECIMAL(5,2)) AS vat_pct,
  CAST(i.discount AS DECIMAL(7,4)) AS header_discount_pct,

  -- References
  i.contractuuid, 
  i.projectuuid, 
  i.invoice_ref_uuid AS source_invoice_uuid, 
  i.creditnote_for_uuid,

  -- Bill-to snapshot (map legacy address fields)
  i.clientname AS bill_to_name,
  i.attention  AS bill_to_attn,
  i.clientaddresse AS bill_to_line1,
  i.otheraddressinfo AS bill_to_line2,
  SUBSTRING_INDEX(i.zipcity, ' ', 1) AS bill_to_zip,
  SUBSTRING_INDEX(i.zipcity, ' ', -1) AS bill_to_city,
  NULL AS bill_to_country,
  i.ean AS bill_to_ean,
  i.cvr AS bill_to_cvr,

  -- ERP integration
  i.economics_voucher_number,
  NULL AS pdf_url,
  NULL AS pdf_sha256

FROM invoices i;

-- ============================================================================
-- 5. INITIALIZE invoice_number_sequences FROM EXISTING DATA
-- ============================================================================

INSERT INTO invoice_number_sequences (issuer_companyuuid, invoice_series, last_number)
SELECT 
  issuer_companyuuid,
  COALESCE(invoice_series, '') AS invoice_series,
  COALESCE(MAX(invoicenumber), 0) AS last_number
FROM invoices_v2
WHERE invoicenumber IS NOT NULL
GROUP BY issuer_companyuuid, COALESCE(invoice_series, '')
ON DUPLICATE KEY UPDATE 
  last_number = GREATEST(last_number, VALUES(last_number));

-- ============================================================================
-- 6. CREATE BACKWARD COMPATIBILITY VIEW
-- ============================================================================

CREATE OR REPLACE VIEW invoices_legacy AS
SELECT
  i.uuid,
  i.issuer_companyuuid AS companyuuid,
  i.debtor_companyuuid,
  COALESCE(i.invoicenumber, 0) AS invoicenumber,
  i.contractuuid,
  i.projectuuid,
  i.invoicedate,
  i.duedate,
  i.bookingdate,
  i.currency,
  CAST(i.vat_pct AS DOUBLE) AS vat,
  CAST(i.header_discount_pct AS DOUBLE) AS discount,
  i.bill_to_name AS clientname,
  i.bill_to_line1 AS clientaddresse,
  i.bill_to_line2 AS otheraddressinfo,
  CONCAT_WS(' ', i.bill_to_zip, i.bill_to_city) AS zipcity,
  i.bill_to_ean AS ean,
  i.bill_to_cvr AS cvr,
  i.bill_to_attn AS attention,
  i.source_invoice_uuid AS invoice_ref_uuid,
  i.creditnote_for_uuid,
  i.economics_voucher_number,
  
  -- Legacy consolidated status field
  CASE
    WHEN i.type = 'CREDIT_NOTE' THEN 'CREDIT_NOTE'
    WHEN i.processing_state = 'QUEUED' THEN 'QUEUED'
    ELSE i.lifecycle_status
  END AS status,
  
  -- Expose new fields for gradual migration
  i.type,
  i.lifecycle_status,
  i.processing_state,
  i.queue_reason,
  
  -- Legacy economics_status mapping
  CASE
    WHEN i.finance_status = 'NONE' THEN 'NA'
    WHEN i.finance_status = 'ERROR' THEN 'NA'
    ELSE i.finance_status
  END AS economics_status,
  
  i.finance_status,
  i.created_at,
  i.updated_at
FROM invoices_v2 i;

-- ============================================================================
-- 7. VALIDATION QUERIES (as comments for verification)
-- ============================================================================

-- Expected: 0 rows (all internal invoices must have debtor)
-- SELECT uuid, type FROM invoices_v2 
-- WHERE type IN ('INTERNAL','INTERNAL_SERVICE') AND debtor_companyuuid IS NULL;

-- Expected: 0 rows (drafts can't have numbers; non-phantoms must have numbers when finalized)
-- SELECT uuid, type, lifecycle_status, invoicenumber FROM invoices_v2
-- WHERE (lifecycle_status='DRAFT' AND invoicenumber IS NOT NULL)
--    OR (lifecycle_status<>'DRAFT' AND type<>'PHANTOM' AND invoicenumber IS NULL);

-- Expected: 0 rows (VAT validation)
-- SELECT uuid, currency, vat_pct FROM invoices_v2
-- WHERE (currency='DKK' AND vat_pct NOT IN (0.00,25.00))
--    OR (currency<>'DKK' AND vat_pct<>0.00);

-- Expected: 0 rows (credit notes can't point to other credit notes)
-- SELECT cn.uuid FROM invoices_v2 cn
-- JOIN invoices_v2 base ON base.uuid = cn.creditnote_for_uuid
-- WHERE cn.type='CREDIT_NOTE' AND base.type='CREDIT_NOTE';

-- Row count verification
-- SELECT 
--   (SELECT COUNT(*) FROM invoices) AS old_count,
--   (SELECT COUNT(*) FROM invoices_v2) AS new_count,
--   (SELECT COUNT(*) FROM invoices_legacy) AS view_count;

COMMIT;

-- ============================================================================
-- Migration Complete
-- ============================================================================
-- Next steps:
-- 1. Run validation queries above to verify data integrity
-- 2. Update application to read from invoices_v2 or invoices_legacy view
-- 3. Implement new domain models and services per backend-developer_guide.md
-- 4. Create v2 API endpoints exposing separated status fields
-- 5. Maintain v1 API compatibility via invoices_legacy view
-- ============================================================================
