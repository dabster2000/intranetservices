-- V555: onboarding_upload_submissions.storage_target — default to 'S3', drop
-- the storage CHECK.
--
-- Why (SharePoint deletion release, WP7): the application no longer knows
-- about storage targets — every onboarding upload lands in S3 (candidate
-- staging or the employee document store), and the entity has stopped
-- mapping storage_target / sharepoint_drive_item_id / sharepoint_web_url /
-- s3_retention_until. The column is NOT NULL with no default, so an INSERT
-- that omits it would fail; giving it DEFAULT 'S3' keeps writes working.
--
-- Two-step rule: ECS Express runs the old and the new task side by side
-- during a canary, so this release must not DROP anything the old task
-- still writes. The column (and the other SharePoint columns) are dropped
-- by a separate later release, once no running task references them.
--
-- The CHECK chk_ous_storage (target <-> identifier consistency) is dropped
-- and deliberately NOT replaced: five legacy SHAREPOINT rows would violate
-- any S3-only shape, and the new writer no longer sets the identifiers the
-- constraint keyed on.
--
-- Idempotent for repair-at-start re-runs: MODIFY is naturally idempotent;
-- the constraint drop is guarded via information_schema + PREPARE/EXECUTE
-- (no DROP CONSTRAINT IF EXISTS for CHECK across the MariaDB versions in use).
--
-- Reserved words checked: storage_target, chk_ous_storage — neither is a
-- MariaDB reserved word (cf. the V534 LINES incident).

ALTER TABLE onboarding_upload_submissions
    MODIFY storage_target ENUM('S3','SHAREPOINT') NOT NULL DEFAULT 'S3';

SET @drop_chk := (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE onboarding_upload_submissions DROP CONSTRAINT chk_ous_storage',
              'SELECT 1')
      FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'onboarding_upload_submissions'
       AND CONSTRAINT_NAME = 'chk_ous_storage'
       AND CONSTRAINT_TYPE = 'CHECK'
);
PREPARE stmt FROM @drop_chk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
