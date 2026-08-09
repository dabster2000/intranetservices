-- V478: invoice_economics_uploads.status gains ABANDONED — a human-reviewed terminal state.
--
-- FAILED uploads that exhaust max_attempts are dead-lettered: the retry poller skips them
-- forever (retryable=0) and until 2026-08 they existed only as an INFO-level counter — five
-- vouchers sat invisible from February to August 2026. ABANDONED closes such a row after a
-- human verifies the document already exists in e-conomic (retrying would double-post it).
-- ABANDONED rows leave the failure counts and the ECONOMICS_UPLOAD_TERMINAL_FAILED alarm,
-- and are kept for audit.
--
-- The Java enum also declares DRAFT_CREATED / BOOK_PENDING / BOOKED; no code path writes
-- them, so they are deliberately NOT added here — the DB enum lists only storable states.
--
-- Appending to the end of a MariaDB ENUM is a metadata-only change; re-running this
-- statement against the already-widened column succeeds with the identical definition.
ALTER TABLE invoice_economics_uploads
    MODIFY COLUMN status ENUM('PENDING','SUCCESS','FAILED','ABANDONED') NOT NULL DEFAULT 'PENDING';
