-- ===================================================================
-- V561: Let a candidate get past a wrong AI rejection.
-- ===================================================================
-- Context: the AI document gate on the public upload page is fail-closed
-- and had no human override anywhere. When the model behind it started
-- answering "reject" to everything (2026-08-25 → 2026-09-02, 20 uploads,
-- 0 approvals), every new hire was simply stuck: re-uploading the same
-- correct photo produced the same refusal, and the page has no way to
-- escalate. Thomas Rask reported it as "the link is expired" because
-- that is what a page that will not accept anything looks like.
--
-- The fix gives the candidate a way out that does NOT weaken the gate:
-- after the SERVER has itself recorded two AI rejections for the same
-- (token, document_type), the page offers "submit anyway", the file is
-- stored, and HR is told it skipped AI approval.
--
-- Why a table and not a client-side counter: the upload endpoint is
-- @PermitAll — the token UUID is the only credential. A "I was rejected
-- twice, let me through" claim from the client would be a one-request
-- bypass of the whole gate for any unauthenticated caller. The count
-- must be one the server wrote itself, and it must survive both a task
-- restart and a candidate whose two attempts land on two ECS tasks.
--
-- Reserved-word check (MariaDB 10.x): `attempts`, `rejection_count` and
-- `manual_review_required` are not reserved (the V534 LINES lesson).
-- Idempotency: repair-at-start re-runs migrations across checkouts —
-- every statement below is IF NOT EXISTS.

-- The ALTER needs an exclusive metadata lock, and a queued exclusive
-- request blocks new shared readers on the same table. Fail fast rather
-- than hang the boot (the V557 lesson).
SET SESSION lock_wait_timeout = 20;

-- ---- server-side AI rejection tally, per token+document type ---------
CREATE TABLE IF NOT EXISTS onboarding_upload_attempts (
    uuid               VARCHAR(36) NOT NULL PRIMARY KEY,
    token_uuid         VARCHAR(36) NOT NULL COMMENT 'Soft-FK to onboarding_upload_tokens.uuid',
    document_type      ENUM('DRIVERS_LICENSE','HEALTH_INSURANCE','CRIMINAL_RECORD') NOT NULL,
    ai_rejection_count INT         NOT NULL DEFAULT 0
                       COMMENT 'AI refusals the server itself recorded; gates the submit-anyway offer',
    last_rejected_at   DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    -- Carries the upsert: INSERT .. ON DUPLICATE KEY UPDATE increments
    -- atomically, so two racing uploads of the same type cannot both read
    -- "1" and both write "2".
    UNIQUE KEY uk_oua_token_doctype (token_uuid, document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per (token, type) count of AI document-gate rejections; see V561';

-- No rows are backfilled on purpose. The count means "refusals observed
-- since the override existed", and the eight-day outage left no record of
-- the 20 historical refusals to backfill from — that absence is the other
-- half of this incident and is fixed in the code, not here.

-- ---- mark uploads that bypassed the AI gate --------------------------
-- Read by the HR Slack notification and available to any later review UI.
-- Defaults to 0, so every row stored before this release keeps its meaning
-- ("the AI approved it") without a data migration.
ALTER TABLE onboarding_upload_submissions
    ADD COLUMN IF NOT EXISTS manual_review_required TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'Stored via submit-anyway after repeated AI rejections; needs a human look';
