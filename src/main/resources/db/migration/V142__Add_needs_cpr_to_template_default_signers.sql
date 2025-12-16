-- =====================================================
-- V142: Add needs_cpr column to template_default_signers
-- =====================================================
-- Purpose: Enable per-signer CPR validation requirement for
--          MitID authentication during digital signing.
--
-- The 'needs_cpr' column determines whether the signer must
-- verify their identity via MitID with CPR validation:
--   - FALSE (default): No CPR validation required
--   - TRUE: Signer must authenticate with MitID Substantial
--           and enter their CPR for identity verification
--
-- Author: Claude Code
-- Date: 2025-12-13
-- =====================================================

ALTER TABLE template_default_signers
ADD COLUMN needs_cpr BOOLEAN NOT NULL DEFAULT FALSE
COMMENT 'TRUE = signer must verify identity with CPR, FALSE = no CPR validation';
