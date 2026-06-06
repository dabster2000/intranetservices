-- Phantom consolidated settlement — additive, inert foundation (Phase 2).
-- No backfill here (Decision D5 backfill is a separate, gated phase). Existing
-- rows keep NULL settlement columns; the new link table starts empty.

-- 1) Settlement-group key on invoices (for INTERNAL / INTERNAL_SERVICE rows).
--    Four discrete nullable columns (planning decision P4); composite-indexed
--    for O(1) group lookup.
--    Columns are APPENDED LAST (no AFTER clause) so MariaDB 10.11 can satisfy
--    ALGORITHM=INPLACE with an INSTANT add — adding a column in a non-final
--    position cannot use INSTANT and would force a full table rebuild during the
--    boot-time Flyway migration. Physical column order is cosmetic here: Hibernate
--    maps by @Column(name=...), not position.
ALTER TABLE invoices
  ADD COLUMN settlement_billing_client_uuid VARCHAR(36) NULL,
  ADD COLUMN settlement_debtor_companyuuid  VARCHAR(36) NULL,
  ADD COLUMN settlement_year                INT         NULL,
  ADD COLUMN settlement_month               INT         NULL,
  ALGORITHM=INPLACE, LOCK=NONE;

CREATE INDEX idx_invoices_settlement_group
  ON invoices (settlement_billing_client_uuid, settlement_debtor_companyuuid, settlement_year, settlement_month)
  ALGORITHM=INPLACE, LOCK=NONE;

-- 2) Auditable link: which phantoms each issued internal/credit-note covered,
--    with the contribution captured at issue time (re-settlement deltas use this).
CREATE TABLE internal_invoice_phantom_link (
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
