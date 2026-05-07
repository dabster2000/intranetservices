-- ===================================================================
-- Migration: V324__Remove_template_signing_stores.sql
-- Description: Collapse the template_signing_stores indirection. A
--              signing case now references a sharepoint_locations row
--              directly via sharepoint_location_uuid, and the per-
--              template store table is removed entirely.
-- Author:      Claude Code
-- Date:        2026-05-07
-- ===================================================================
--
-- Background:
--   V132 introduced template_signing_stores as a 1:1 wrapper between a
--   document_template and a SharePoint folder. V139/V140 then split out
--   the SharePoint folder into the shared sharepoint_locations library,
--   leaving template_signing_stores as a thin (uuid, template_uuid,
--   location_uuid, display_name_override) join row.
--
--   After V323 (company + type on sharepoint_locations, sharepoint_type
--   on document_templates) a signing case can resolve its target folder
--   directly:
--       sharepoint_locations
--         WHERE company_uuid = signing_case.company_uuid
--           AND type        = template.sharepoint_type
--   The template_signing_stores indirection no longer carries any
--   information that isnt already expressible through that lookup, so
--   we collapse it.
--
-- Migration steps:
--   1. Add signing_cases.sharepoint_location_uuid (nullable).
--   2. Backfill it from the existing signing_store -> location chain.
--   3. Drop signing_cases.signing_store_uuid (it has no DB-level FK,
--      only a soft reference, so no DROP FOREIGN KEY is required).
--   4. Drop template_signing_stores entirely (its FK to
--      sharepoint_locations is dropped first to keep MariaDB happy).
--
-- Backwards compatibility:
--   sharepoint_location_uuid is nullable on signing_cases — existing
--   cases without an auto-upload target keep NULL. New cases will be
--   populated by application code (post-deploy) based on company + type.
--
-- Rollback:
--   See bottom of file. Note that step 4 (dropping
--   template_signing_stores) is destructive; the rollback recreates the
--   table shape but cannot recover deleted rows. Take a backup of
--   template_signing_stores before applying.
-- ===================================================================

START TRANSACTION;

-- -------------------------------------------------------------------
-- 1. Add the new direct reference on signing_cases
-- -------------------------------------------------------------------
ALTER TABLE signing_cases
    ADD COLUMN sharepoint_location_uuid VARCHAR(36) NULL
        COMMENT 'Direct reference to sharepoint_locations.uuid for auto-upload of signed document. Populated at case creation from (company_uuid, template.sharepoint_type). NULL = no auto-upload.'
        AFTER signing_store_uuid;

-- -------------------------------------------------------------------
-- 2. Backfill from the old signing_store -> location chain.
--    template_signing_stores.location_uuid was made NOT NULL in V140,
--    so any signing_case with a signing_store_uuid will get a value.
-- -------------------------------------------------------------------
UPDATE signing_cases sc
    JOIN template_signing_stores tss
        ON sc.signing_store_uuid = tss.uuid
SET sc.sharepoint_location_uuid = tss.location_uuid
WHERE sc.signing_store_uuid IS NOT NULL;

-- -------------------------------------------------------------------
-- 3. Drop the now-redundant soft reference column.
--    No FK exists on this column (V133 deliberately left it as a soft
--    reference), so a plain DROP COLUMN is sufficient. We also drop
--    the index added in V133 to avoid orphan-index warnings.
-- -------------------------------------------------------------------
ALTER TABLE signing_cases
    DROP INDEX  idx_sc_signing_store,
    DROP COLUMN signing_store_uuid;

-- Helpful index for the new column (pending-upload batch jobs etc.)
CREATE INDEX idx_sc_sharepoint_location ON signing_cases(sharepoint_location_uuid);

-- -------------------------------------------------------------------
-- 4. Drop template_signing_stores.
--    Drop the FK to sharepoint_locations first (named in V140) so the
--    parent table is releasable.
-- -------------------------------------------------------------------
ALTER TABLE template_signing_stores
    DROP FOREIGN KEY fk_template_signing_stores_location;

DROP TABLE template_signing_stores;

COMMIT;

-- ===================================================================
-- Validation queries (run manually after deploy)
-- ===================================================================
--
-- New column exists and old one is gone:
--   SHOW COLUMNS FROM signing_cases LIKE 'sharepoint_location_uuid';
--   -- expected: 1 row, type varchar(36), Null = YES
--   SHOW COLUMNS FROM signing_cases LIKE 'signing_store_uuid';
--   -- expected: empty
--
-- Backfill landed (every case that previously had a signing store now
-- has a SharePoint location):
--   -- Run BEFORE this migration to capture the baseline:
--   --   SELECT COUNT(*) FROM signing_cases WHERE signing_store_uuid IS NOT NULL;
--   -- After this migration the same count should appear here:
--   SELECT COUNT(*) FROM signing_cases WHERE sharepoint_location_uuid IS NOT NULL;
--
-- All sharepoint_location_uuid values resolve to a real location:
--   SELECT COUNT(*)
--     FROM signing_cases sc
--     LEFT JOIN sharepoint_locations sl
--       ON sc.sharepoint_location_uuid = sl.uuid
--    WHERE sc.sharepoint_location_uuid IS NOT NULL
--      AND sl.uuid IS NULL;
--   -- expected: 0
--
-- template_signing_stores is gone:
--   SHOW TABLES LIKE 'template_signing_stores';
--   -- expected: empty
--
-- ===================================================================
-- Impact on Quarkus code (informational, not part of the migration)
-- ===================================================================
-- Affected classes (must be removed / updated in the same release):
--   * Entity: dk.trustworks.intranet.documentservice.model.TemplateSigningStore
--       -> delete entire class.
--   * Repository / panache queries on TemplateSigningStore -> delete.
--   * REST resource: dk.trustworks.intranet.documentservice.resources
--       .TemplateSigningStoreResource (if any) -> delete.
--   * SigningCase entity:
--       - remove field signingStoreUuid
--       - add    field sharepointLocationUuid (or @ManyToOne SharepointLocation)
--   * Any service that wrote signing_store_uuid on case creation must
--     instead resolve sharepoint_location_uuid from
--     (case.company_uuid, template.sharepoint_type) introduced in V323.
--
-- DEPLOY ORDER REQUIREMENT:
--   Application code that still reads/writes signing_store_uuid or the
--   template_signing_stores table must be removed in the SAME release
--   that ships V324. Otherwise the app will throw at startup
--   (missing column / table). If a phased rollout is required, do
--   V323 + code migration first, then V324 in a follow-up release.
-- ===================================================================
-- Rollback (manual; data in template_signing_stores is NOT recoverable
-- without a backup taken before this migration ran)
-- ===================================================================
-- BEGIN;
-- CREATE TABLE template_signing_stores (
--     uuid                  VARCHAR(36) PRIMARY KEY,
--     template_uuid         VARCHAR(36) NOT NULL,
--     location_uuid         VARCHAR(36) NOT NULL,
--     display_name_override VARCHAR(255),
--     is_active             BOOLEAN DEFAULT TRUE,
--     display_order         INT NOT NULL DEFAULT 1,
--     created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
--     updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
--                                ON UPDATE CURRENT_TIMESTAMP NOT NULL,
--     CONSTRAINT fk_template_signing_stores_template_uuid
--         FOREIGN KEY (template_uuid) REFERENCES document_templates(uuid)
--         ON DELETE CASCADE ON UPDATE CASCADE,
--     CONSTRAINT fk_template_signing_stores_location
--         FOREIGN KEY (location_uuid) REFERENCES sharepoint_locations(uuid)
--         ON DELETE RESTRICT ON UPDATE CASCADE,
--     CONSTRAINT uk_template_signing_stores_template_uuid
--         UNIQUE (template_uuid)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- -- Restore template_signing_stores rows from backup here.
-- ALTER TABLE signing_cases
--     ADD COLUMN signing_store_uuid VARCHAR(36) NULL AFTER status_fetch_error;
-- CREATE INDEX idx_sc_signing_store ON signing_cases(signing_store_uuid);
-- -- Optional: rebuild signing_store_uuid from the (template, location)
-- -- pair if the restored template_signing_stores allows it.
-- ALTER TABLE signing_cases
--     DROP INDEX  idx_sc_sharepoint_location,
--     DROP COLUMN sharepoint_location_uuid;
-- COMMIT;
-- ===================================================================
