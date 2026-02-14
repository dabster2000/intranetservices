-- ============================================================================
-- V174: Add pdf_storage_key column for S3-based PDF storage
-- Part of Phase 2.3: Extract PDF to External Storage
--
-- Adds a column to track the S3 object key for each invoice's PDF.
-- Once all PDFs are migrated to S3, the pdf LONGBLOB column can be dropped.
-- ============================================================================

ALTER TABLE invoices ADD COLUMN pdf_storage_key VARCHAR(255) DEFAULT NULL;
CREATE INDEX idx_invoices_pdf_storage_key ON invoices(pdf_storage_key);
