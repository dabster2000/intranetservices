-- =============================================================================
-- V388: Client×month controlling approvals + notes
--
-- GOAL
--   Let the invoice-controlling team sign off on a client's billing for a given
--   month ("approve" a heatmap cell) and attach one editable note per cell, so
--   that already-controlled months drop out of the under-billing worklist and
--   the AI account-manager brief instead of being re-raised every day.
--
-- WHAT THIS ADDS
--   1. client_month_control — one row per (client, month) carrying the current
--      approval snapshot and the single editable note. The approval freezes the
--      expected/invoiced values at sign-off time (approved_expected /
--      approved_invoiced) so drift can be detected later: if the live values move
--      more than the tolerance (1.000 kr) from the snapshot, the cell is flagged
--      and re-enters the worklist. A note may exist without an approval.
--   2. client_month_control_history — append-only audit trail: one row per change
--      (APPROVED | REAPPROVED | UNAPPROVED | NOTE_UPDATED | BULK_APPROVED),
--      capturing who/when and the snapshot values at the time of the action.
--
-- IDEMPOTENT / RE-RUNNABLE
--   Every statement uses IF [NOT] EXISTS so a partially-applied or interrupted
--   run re-runs cleanly (MariaDB auto-commits each DDL). No FK constraints (the
--   application maintains integrity) to avoid charset/collation coupling and
--   canary alter hazards; cross-aggregate references (client_uuid, approved_by,
--   created_by, ...) are plain UUID columns. month is stored first-of-month.
-- =============================================================================

CREATE TABLE IF NOT EXISTS client_month_control (
    uuid                VARCHAR(36)  NOT NULL,
    client_uuid         VARCHAR(36)  NOT NULL,
    month               DATE         NOT NULL,
    approved_at         DATETIME     NULL,
    approved_by         VARCHAR(36)  NULL,
    approved_expected   DOUBLE       NULL,
    approved_invoiced   DOUBLE       NULL,
    note                TEXT         NULL,
    created_at          DATETIME     NOT NULL,
    created_by          VARCHAR(36)  NULL,
    updated_at          DATETIME     NULL,
    updated_by          VARCHAR(36)  NULL,
    CONSTRAINT pk_client_month_control PRIMARY KEY (uuid),
    CONSTRAINT uq_client_month_control UNIQUE (client_uuid, month)
);

CREATE INDEX IF NOT EXISTS idx_client_month_control_month
    ON client_month_control (month);

CREATE TABLE IF NOT EXISTS client_month_control_history (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    control_uuid        VARCHAR(36)  NOT NULL,
    client_uuid         VARCHAR(36)  NOT NULL,
    month               DATE         NOT NULL,
    action              VARCHAR(30)  NOT NULL,
    note                TEXT         NULL,
    approved_expected   DOUBLE       NULL,
    approved_invoiced   DOUBLE       NULL,
    changed_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by          VARCHAR(36)  NULL,
    CONSTRAINT pk_client_month_control_history PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_client_month_control_history_client_month
    ON client_month_control_history (client_uuid, month);
