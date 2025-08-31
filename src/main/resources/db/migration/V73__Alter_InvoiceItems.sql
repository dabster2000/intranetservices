ALTER TABLE invoiceitems ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE invoiceitems ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE invoiceitems ADD COLUMN rulecode VARCHAR(64) NULL;
ALTER TABLE invoiceitems ADD COLUMN calc_note VARCHAR(255) NULL;
CREATE INDEX ix_invoiceitems_rulecode ON invoiceitems(rulecode);