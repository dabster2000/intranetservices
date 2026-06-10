-- ====================================================================
-- V366: Self-billed workbench — mark/unmark audit columns.
--
-- Records the actor (UUID) and timestamp of the LAST human mark or
-- unmark transition on a selfbilled_line row, durable across re-captures
-- (Amendment #3, Task 8). Both columns are nullable — unset means the
-- line has never been manually marked or unmarked.
--
-- Rollback: ALTER TABLE selfbilled_line DROP COLUMN marked_by;
--           ALTER TABLE selfbilled_line DROP COLUMN marked_at;
-- ====================================================================

ALTER TABLE selfbilled_line
    ADD COLUMN IF NOT EXISTS marked_by VARCHAR(36) NULL
        COMMENT 'Actor UUID (X-Requested-By) of the last mark/unmark transition'
        AFTER refreshed_at,
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE selfbilled_line
    ADD COLUMN IF NOT EXISTS marked_at DATETIME NULL
        COMMENT 'Timestamp of the last mark/unmark transition'
        AFTER marked_by,
    ALGORITHM=INPLACE, LOCK=NONE;
