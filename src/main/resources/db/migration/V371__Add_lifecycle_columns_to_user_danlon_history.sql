-- ====================================================================
-- V371: Lifecycle columns on user_danlon_history (spec §4.3).
--
--   company_uuid   — stamped on every NEW mint (legacy rows stay NULL;
--                    foundation for the Approach-B fast-follow)
--   closed_date    — set when a slot is retired; cleared on reopen
--   closed_reason  — why it was retired
--   event_type     — the slot's event; mirrors today's created_by marker
--                    so reopen/close match a slot without parsing created_by
--
-- event_type is backfilled from the existing system-* created_by markers
-- (pure column copy, no semantic change). PK and UNIQUE(useruuid,
-- active_date) are unchanged. NO UNIQUE(danlon) here.
--
-- Additive, online (ALGORITHM=INSTANT for the ADD COLUMNs).
-- Rollback:
--   ALTER TABLE user_danlon_history
--     DROP COLUMN company_uuid, DROP COLUMN closed_date,
--     DROP COLUMN closed_reason, DROP COLUMN event_type;
-- ====================================================================

ALTER TABLE user_danlon_history
    ADD COLUMN IF NOT EXISTS company_uuid  VARCHAR(36)   NULL COMMENT 'Target company of the mint (NULL on legacy rows)' AFTER danlon,
    ADD COLUMN IF NOT EXISTS closed_date   DATETIME      NULL COMMENT 'Set when the slot is retired; cleared on reopen'   AFTER created_by,
    ADD COLUMN IF NOT EXISTS closed_reason VARCHAR(1024) NULL COMMENT 'Why the number was retired'                        AFTER closed_date,
    ADD COLUMN IF NOT EXISTS event_type    VARCHAR(40)   NULL COMMENT 'Slot event: FIRST_EMPLOYMENT|RE_EMPLOYMENT|COMPANY_TRANSITION|SALARY_TYPE_CHANGE' AFTER closed_reason,
    ALGORITHM=INSTANT;

-- Backfill event_type from the legacy system-* created_by markers.
-- Migration/manual rows (system-migration, usernames, '0') stay NULL.
UPDATE user_danlon_history
SET event_type = CASE created_by
        WHEN 'system-first-employment'   THEN 'FIRST_EMPLOYMENT'
        WHEN 'system-re-employment'      THEN 'RE_EMPLOYMENT'
        WHEN 'system-company-transition' THEN 'COMPANY_TRANSITION'
        WHEN 'system-salary-type-change' THEN 'SALARY_TYPE_CHANGE'
        ELSE event_type
    END
WHERE created_by IN (
    'system-first-employment', 'system-re-employment',
    'system-company-transition', 'system-salary-type-change'
);
