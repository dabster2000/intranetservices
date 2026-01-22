-- =====================================================================================
-- Migration: V150__Create_invoice_control_history_table.sql
-- Description: Create invoice control history table to track all status changes
-- Author: Claude Code
-- Date: 2026-01-22
-- =====================================================================================
--
-- This migration creates a history table to track all invoice control status changes:
--   - Audit trail of all status changes with timestamps
--   - Full history for timeline visualization
--   - User attribution for each change
--   - Optional notes for each status change
--
-- This enables the invoice-controlling-admin page to display a complete timeline
-- of status changes for CLIENT invoices.
-- =====================================================================================

-- Create invoice control history table
CREATE TABLE invoice_control_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    invoice_uuid VARCHAR(36) NOT NULL COMMENT 'Invoice UUID (foreign key to invoices.uuid)',
    control_status VARCHAR(20) NOT NULL COMMENT 'Control status at this point (NOT_REVIEWED, UNDER_REVIEW, APPROVED, REJECTED)',
    control_note TEXT NULL COMMENT 'Optional reviewer notes for this status change',
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp when status was changed',
    changed_by VARCHAR(255) NOT NULL COMMENT 'User UUID who made the change',

    -- Foreign key constraint
    CONSTRAINT fk_invoice_control_history_invoice
        FOREIGN KEY (invoice_uuid) REFERENCES invoices(uuid)
        ON DELETE CASCADE,

    -- Index for efficient queries
    INDEX idx_invoice_control_history_invoice (invoice_uuid),
    INDEX idx_invoice_control_history_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Audit trail of invoice control status changes';

-- Populate history with current status for existing invoices
-- (Only for invoices that have been reviewed, i.e., not still in NOT_REVIEWED with null timestamps)
INSERT INTO invoice_control_history (invoice_uuid, control_status, control_note, changed_at, changed_by)
SELECT
    uuid,
    control_status,
    control_note,
    COALESCE(control_status_updated_at, invoicedate),
    COALESCE(control_status_updated_by, 'system')
FROM invoices
WHERE control_status_updated_at IS NOT NULL;
