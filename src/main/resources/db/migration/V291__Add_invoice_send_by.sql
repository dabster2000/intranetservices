ALTER TABLE invoices ADD COLUMN send_by VARCHAR(10) NULL
    COMMENT 'Delivery method used at booking time: ean, Email, or NULL (no delivery)';
