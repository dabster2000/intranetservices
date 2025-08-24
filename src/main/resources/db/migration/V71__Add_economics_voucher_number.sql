ALTER TABLE invoices
  ADD COLUMN economics_voucher_number INT NOT NULL DEFAULT 0;

CREATE INDEX idx_invoices_economics_voucher_number ON invoices (economics_voucher_number);