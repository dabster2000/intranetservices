-- RELEASE GATE: stop admission and ALL old mail workers before applying this migration.
-- Existing origin cannot be inferred from addresses. Review held rows using trustworthy provenance.
ALTER TABLE conference_phases ADD COLUMN unsubscribe_footer JSON NULL;
ALTER TABLE mail
    MODIFY COLUMN mail VARCHAR(320) NULL,
    MODIFY COLUMN status VARCHAR(20),
    ADD COLUMN conference_uuid CHAR(36) NULL,
    ADD COLUMN participant_uuid CHAR(36) NULL,
    ADD COLUMN normalized_email VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN unsubscribe_footer JSON NULL,
    ADD COLUMN mail_origin VARCHAR(16) NULL,
    ADD COLUMN skip_reason VARCHAR(32) NULL,
    ADD COLUMN hold_reason VARCHAR(32) NULL;
ALTER TABLE bulk_email_job
    MODIFY COLUMN status ENUM('PENDING','PROCESSING','POLICY_PENDING','POLICY_PROCESSING','COMPLETED','FAILED','HELD') NOT NULL DEFAULT 'PENDING',
    ADD COLUMN conference_uuid CHAR(36) NULL,
    ADD COLUMN unsubscribe_footer JSON NULL,
    ADD COLUMN mail_origin VARCHAR(16) NULL,
    ADD COLUMN hold_reason VARCHAR(32) NULL,
    ADD COLUMN skipped_count INT NOT NULL DEFAULT 0;
ALTER TABLE bulk_email_recipient
    MODIFY COLUMN status ENUM('PENDING','SENT','FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
    MODIFY COLUMN recipient_email VARCHAR(320) NOT NULL,
    ADD COLUMN recipient_name VARCHAR(255) NULL,
    ADD COLUMN conference_uuid CHAR(36) NULL,
    ADD COLUMN participant_uuid CHAR(36) NULL,
    ADD COLUMN normalized_email VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN skip_reason VARCHAR(32) NULL;

-- Persisted quarantine; never silently classify a legacy message as unrelated system mail.
UPDATE mail SET status = 'HELD', hold_reason = 'LEGACY_UNCLASSIFIED' WHERE status = 'READY';
UPDATE bulk_email_job SET status = 'HELD', hold_reason = 'LEGACY_UNCLASSIFIED'
 WHERE status IN ('PENDING','PROCESSING');
-- POLICY_* statuses are intentionally unknown to old workers, preventing a rolling-version bypass.
-- Operators must retain an audited resolution list; release confirmed SYSTEM rows only, or recreate
-- known conference messages through the typed conference admission path after the launch gate passes.
