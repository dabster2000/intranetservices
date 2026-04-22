-- =============================================================================
-- Migration V302: Widen attribution_audit_log.change_type
--
-- Purpose:
--   The column was originally VARCHAR(20), which is not enough to hold two of
--   the change_type values the service actually writes:
--     ADMIN_FORCE_RECOMPUTE  (21 chars) -- newly added
--     INTERNAL_INVOICE_REGEN (22 chars) -- pre-existing, latent
--   Both were silently failing with "Data too long for column 'change_type'".
--
--   Widening to VARCHAR(50) leaves headroom for future change types without
--   another migration. No row rewrite is needed (MariaDB metadata-only op for
--   VARCHAR growth within the same byte-width class); applies instantly.
-- =============================================================================

ALTER TABLE attribution_audit_log
    MODIFY COLUMN change_type VARCHAR(50) NOT NULL;
