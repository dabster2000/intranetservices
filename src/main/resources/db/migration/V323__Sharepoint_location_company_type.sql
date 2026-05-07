-- ===================================================================
-- Migration: V323__Sharepoint_location_company_type.sql
-- Description: Associate SharePoint locations with companies and a
--              location type (EMPLOYEE / CLIENT / OTHER); add a matching
--              sharepoint_type to document_templates so templates can
--              auto-resolve the correct location for the requesting
--              company.
-- Author:      Claude Code
-- Date:        2026-05-07
-- ===================================================================
--
-- Purpose:
--   Today the three SharePoint locations exist as a flat list (one per
--   Trustworks company, all employee-document folders). This migration
--   makes that relationship explicit:
--
--     * Each sharepoint_locations row is owned by exactly one company.
--     * Each sharepoint_locations row has a "type" describing what kind
--       of documents the folder is intended for (employee documents,
--       client documents, or other).
--     * (company_uuid, type) is unique — i.e. a company has at most one
--       EMPLOYEE folder, one CLIENT folder, etc.
--
--   document_templates gains a corresponding sharepoint_type column so a
--   template can declare which kind of location it should be filed
--   under. Resolution at runtime becomes:
--
--     sharepoint_locations
--       WHERE company_uuid = <signing case company>
--         AND type        = <template.sharepoint_type>
--
--   sharepoint_type = 'NONE' on a template means "do not auto-file in
--   SharePoint" — useful for ad-hoc / non-archival templates.
--
-- Backfill (safe — explicit UUIDs, confirmed from production data):
--   'TWT Medarbejdere' -> Trustworks Technology ApS
--   'TWC Medarbejdere' -> Trustworks Cyber Security ApS
--   'TW Medarbejdere'  -> Trustworks A/S
--   All three are EMPLOYEE folders, which matches the
--   ENUM column default.
--
-- Backwards compatibility:
--   - Columns are added with safe defaults so existing rows remain
--     valid before backfill.
--   - The pre-existing UNIQUE (site_url, drive_name, folder_path)
--     constraint on sharepoint_locations is intentionally retained;
--     it is orthogonal to the new (company_uuid, type) uniqueness.
--   - All existing document_templates default to sharepoint_type =
--     'EMPLOYEE' which preserves current employee-document behaviour.
--
-- Rollback strategy:
--   See "Rollback" section at the bottom of this file. This migration
--   does not delete data, so the inverse DDL is sufficient.
-- ===================================================================

START TRANSACTION;

-- -------------------------------------------------------------------
-- 1. sharepoint_locations: add company_uuid + type
-- -------------------------------------------------------------------
ALTER TABLE sharepoint_locations
    ADD COLUMN company_uuid VARCHAR(36) NULL
        COMMENT 'Owning company (FK to companies.uuid). Backfilled in this migration, then made NOT NULL.'
        AFTER folder_path,
    ADD COLUMN type ENUM('EMPLOYEE','CLIENT','OTHER') NOT NULL DEFAULT 'EMPLOYEE'
        COMMENT 'What kind of documents this location stores: EMPLOYEE = employee-related (contracts, addenda), CLIENT = client-facing documents, OTHER = miscellaneous.'
        AFTER company_uuid;

-- -------------------------------------------------------------------
-- 2. Backfill company_uuid for the three known existing rows
--    (UUIDs verified against companies table in prod).
-- -------------------------------------------------------------------
UPDATE sharepoint_locations
SET company_uuid = '44592d3b-2be5-4b29-bfaf-4fafc60b0fa3'  -- Trustworks Technology ApS
WHERE name = 'TWT Medarbejdere'
  AND company_uuid IS NULL;

UPDATE sharepoint_locations
SET company_uuid = 'e4b0a2a4-0963-4153-b0a2-a409637153a2'  -- Trustworks Cyber Security ApS
WHERE name = 'TWC Medarbejdere'
  AND company_uuid IS NULL;

UPDATE sharepoint_locations
SET company_uuid = 'd8894494-2fb4-4f72-9e05-e6032e6dd691'  -- Trustworks A/S
WHERE name = 'TW Medarbejdere'
  AND company_uuid IS NULL;

-- -------------------------------------------------------------------
-- 3. Enforce NOT NULL once backfill is complete.
--    If any sharepoint_locations row is still NULL at this point the
--    migration will (correctly) fail and force manual intervention
--    rather than silently shipping bad data.
-- -------------------------------------------------------------------
ALTER TABLE sharepoint_locations
    MODIFY COLUMN company_uuid VARCHAR(36) NOT NULL
        COMMENT 'Owning company (FK to companies.uuid).';

-- -------------------------------------------------------------------
-- 4. Foreign key + uniqueness
-- -------------------------------------------------------------------
ALTER TABLE sharepoint_locations
    ADD CONSTRAINT fk_sharepoint_locations_company
        FOREIGN KEY (company_uuid)
        REFERENCES companies(uuid)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    ADD CONSTRAINT uq_sharepoint_locations_company_type
        UNIQUE (company_uuid, type);

-- Lookup index for the runtime resolution query
-- (company_uuid, type) is already covered by the unique constraint
-- above, so no extra index is needed.

-- -------------------------------------------------------------------
-- 5. document_templates: add sharepoint_type
-- -------------------------------------------------------------------
ALTER TABLE document_templates
    ADD COLUMN sharepoint_type ENUM('EMPLOYEE','CLIENT','OTHER','NONE') NOT NULL DEFAULT 'EMPLOYEE'
        COMMENT 'Which sharepoint_locations.type this template should be auto-filed under for the signing companys folder. NONE = do not auto-upload.'
        AFTER category;

COMMIT;

-- ===================================================================
-- Validation queries (run manually after deploy)
-- ===================================================================
--
-- Every location now has a company:
--   SELECT COUNT(*) FROM sharepoint_locations WHERE company_uuid IS NULL;
--   -- expected: 0
--
-- Backfill produced the expected mapping:
--   SELECT name, company_uuid, type FROM sharepoint_locations ORDER BY name;
--   -- expected:
--   --   TW Medarbejdere  | d8894494-... | EMPLOYEE
--   --   TWC Medarbejdere | e4b0a2a4-... | EMPLOYEE
--   --   TWT Medarbejdere | 44592d3b-... | EMPLOYEE
--
-- Uniqueness holds:
--   SELECT company_uuid, type, COUNT(*)
--     FROM sharepoint_locations
--    GROUP BY company_uuid, type
--   HAVING COUNT(*) > 1;
--   -- expected: empty
--
-- All templates default to EMPLOYEE:
--   SELECT sharepoint_type, COUNT(*) FROM document_templates GROUP BY sharepoint_type;
--   -- expected: only EMPLOYEE (until product changes a template).
--
-- Foreign key is enforced (this should fail):
--   INSERT INTO sharepoint_locations
--       (uuid, name, site_url, drive_name, folder_path,
--        company_uuid, type, is_active, display_order, created_at, updated_at)
--   VALUES (UUID(), 'broken', 'x', 'y', NULL,
--           '00000000-0000-0000-0000-000000000000', 'EMPLOYEE',
--           TRUE, 99, NOW(), NOW());
--   -- expected: FK violation on companies(uuid).
--
-- ===================================================================
-- Impact on Quarkus code (informational, not part of the migration)
-- ===================================================================
-- Affected entities/repositories (must add fields after deploy):
--   * dk.trustworks.intranet.documentservice.model.SharepointLocation
--       new fields: companyUuid (or @ManyToOne Company),
--                   type (enum LocationType { EMPLOYEE, CLIENT, OTHER })
--   * dk.trustworks.intranet.documentservice.model.DocumentTemplate
--       new field:  sharepointType (enum TemplateSharepointType
--                                   { EMPLOYEE, CLIENT, OTHER, NONE })
--   * Any service that resolves "which folder does this signed doc go in?"
--     must switch from a name-based lookup to:
--         WHERE company_uuid = :companyUuid AND type = :type
-- ===================================================================
-- Rollback (manual)
-- ===================================================================
-- BEGIN;
-- ALTER TABLE document_templates
--     DROP COLUMN sharepoint_type;
-- ALTER TABLE sharepoint_locations
--     DROP FOREIGN KEY fk_sharepoint_locations_company,
--     DROP INDEX       uq_sharepoint_locations_company_type,
--     DROP COLUMN      type,
--     DROP COLUMN      company_uuid;
-- COMMIT;
-- ===================================================================
