-- =====================================================
-- V141: Add signing column to template_default_signers
-- =====================================================
-- Purpose: Distinguish between signers (must sign) and
--          receivers (receive copy only - CC recipients).
--
-- The 'signing' column determines recipient behavior in NextSign:
--   - TRUE (default): Must sign the document
--   - FALSE: Receives a copy but doesn't sign (CC recipient)
--
-- Author: Claude Code
-- Date: 2025-12-13
-- =====================================================

ALTER TABLE template_default_signers
ADD COLUMN signing BOOLEAN NOT NULL DEFAULT TRUE
COMMENT 'TRUE = must sign, FALSE = receives copy only (CC)';
