ALTER TABLE invoices
  ADD COLUMN economics_status VARCHAR(32) NOT NULL DEFAULT 'NA' AFTER status;

CREATE INDEX idx_invoices_economics_status ON invoices (economics_status);
