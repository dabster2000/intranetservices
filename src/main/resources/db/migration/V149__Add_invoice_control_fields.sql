-- =====================================================================================
-- Migration: V149__Add_invoice_control_fields.sql
-- Description: Add invoice control status tracking fields for CLIENT invoices
-- Author: Claude Code
-- Date: 2026-01-22
-- =====================================================================================
--
-- This migration adds invoice control workflow tracking to the invoices table:
--   - control_status: Current review status (NOT_REVIEWED, UNDER_REVIEW, APPROVED, REJECTED)
--   - control_note: Optional notes from reviewer
--   - control_status_updated_at: Timestamp of last status change
--   - control_status_updated_by: User UUID who made the last change
--
-- These fields enable the invoice-controlling-admin page to track and manage
-- the review status of CLIENT invoices with full audit trail.
-- =====================================================================================

-- Add control status columns to invoices table
ALTER TABLE invoices
    ADD COLUMN control_status VARCHAR(20) DEFAULT 'NOT_REVIEWED' COMMENT 'Invoice control review status',
    ADD COLUMN control_note TEXT NULL COMMENT 'Optional reviewer notes',
    ADD COLUMN control_status_updated_at DATETIME NULL COMMENT 'Timestamp of last status change',
    ADD COLUMN control_status_updated_by VARCHAR(255) NULL COMMENT 'User UUID who last updated the status';

-- Create index for filtering by control status
CREATE INDEX idx_invoices_control_status ON invoices(control_status);

-- Create composite index for common query pattern (type + status + control status)
CREATE INDEX idx_invoices_type_status_control ON invoices(type, status, control_status);
