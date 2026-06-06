-- Phantom consolidated settlement — additive, inert foundation (Phase 2).
-- No backfill here (Decision D5 backfill is a separate, gated phase). Existing
-- rows keep NULL settlement columns; the new link table starts empty.
--
-- IDEMPOTENT (IF NOT EXISTS on every object) so the script is safe to re-run after
-- a partial failure. MariaDB auto-commits each DDL statement (no transactional DDL),
-- so an earlier attempt that committed the four ADD COLUMNs before failing on a
-- later statement leaves the columns present but the index/table absent; IF NOT
-- EXISTS lets the corrected script skip the existing columns and create the rest.
--
-- No explicit ALGORITHM/LOCK clause: adding trailing nullable columns is INSTANT and
-- adding a secondary index is INPLACE/online by default on InnoDB (MariaDB 10.11), so
-- both run without blocking DML. (NB: "ALGORITHM=INPLACE, LOCK=NONE" is valid on
-- ALTER TABLE but NOT on CREATE INDEX — so it is omitted to keep the syntax portable.)

-- 1) Settlement-group key on invoices (for INTERNAL / INTERNAL_SERVICE rows).
--    Four discrete nullable columns (planning decision P4); composite-indexed
--    for O(1) group lookup. Columns are appended last (no AFTER) so the add is INSTANT.
ALTER TABLE invoices
  ADD COLUMN IF NOT EXISTS settlement_billing_client_uuid VARCHAR(36) NULL,
  ADD COLUMN IF NOT EXISTS settlement_debtor_companyuuid  VARCHAR(36) NULL,
  ADD COLUMN IF NOT EXISTS settlement_year                INT         NULL,
  ADD COLUMN IF NOT EXISTS settlement_month               INT         NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_settlement_group
  ON invoices (settlement_billing_client_uuid, settlement_debtor_companyuuid, settlement_year, settlement_month);

-- 2) Auditable link: which phantoms each issued internal/credit-note covered,
--    with the contribution captured at issue time (re-settlement deltas use this).
CREATE TABLE IF NOT EXISTS internal_invoice_phantom_link (
    uuid                       VARCHAR(36)   NOT NULL,
    internal_uuid              VARCHAR(36)   NOT NULL,
    phantom_uuid               VARCHAR(36)   NOT NULL,
    attributed_amount_at_issue DECIMAL(15,2) NOT NULL,
    created_at                 DATETIME      NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_iipl_internal_phantom (internal_uuid, phantom_uuid),
    KEY idx_iipl_internal (internal_uuid),
    KEY idx_iipl_phantom (phantom_uuid)
) ENGINE=InnoDB;
