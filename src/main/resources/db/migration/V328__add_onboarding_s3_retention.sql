-- V328: Track S3 retention timestamp on onboarding upload submissions so the
-- existing recruitment retention reaper (S3RetentionCleanupBatchlet) can clean
-- up the original S3 copy after the file has been moved to SharePoint as
-- part of the candidate -> employee promotion flow.
--
-- Mirrors candidate_dossier_revisions.s3_retention_until exactly:
--   * NULL  = not eligible for reaping (default; never moved)
--   * SET   = reap when current time >= value
-- The reaper sets the column back to NULL after the S3 delete succeeds.
ALTER TABLE onboarding_upload_submissions
    ADD COLUMN s3_retention_until DATETIME(6) NULL
        COMMENT 'Eligible for S3 reaping when set and elapsed (mirrors candidate_dossier_revisions.s3_retention_until). NULL on rows that have not been moved to SharePoint or whose owner is the user-flow.',
    ADD INDEX idx_ous_s3_retention (s3_retention_until);
