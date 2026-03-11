-- ============================================================================
-- V242: Create role_definition table for dynamic user access roles
-- ============================================================================
-- Purpose: Migrates access roles from a static Java enum (RoleType) to a
--          database-backed table, enabling runtime creation of new roles
--          without code changes or redeployment.
--
-- Changes:
--   1. Creates `role_definition` table (name PK, display_label, is_system, timestamps)
--   2. Seeds all 22 known roles (union of backend enum + frontend + @RolesAllowed usage)
--   3. Widens `roles.role` from VARCHAR(16) to VARCHAR(50) for future role names
--   4. Adds FK constraint: roles.role -> role_definition.name
--
-- Backwards compatibility:
--   - Existing roles data is unchanged (all current values are seeded)
--   - roles.role remains VARCHAR (was already VARCHAR(16), now VARCHAR(50))
--   - ON UPDATE CASCADE ensures role renames propagate to assignments
--   - ON DELETE RESTRICT prevents deleting in-use role definitions
--
-- Rollback strategy:
--   ALTER TABLE roles DROP FOREIGN KEY fk_roles_role_definition;
--   ALTER TABLE roles MODIFY COLUMN role VARCHAR(16) NOT NULL;
--   DROP TABLE role_definition;
--
-- Impact:
--   - Quarkus entities: Role.java (role field type stays String, width changes)
--   - New entity needed: RoleDefinition.java
--   - Repositories: RoleService.java must validate against role_definition
--
-- Author: Claude Code
-- Date: 2026-03-11
-- ============================================================================

-- Step 1: Create the role_definition table
CREATE TABLE role_definition (
    name         VARCHAR(50)  NOT NULL,
    display_label VARCHAR(100) NOT NULL,
    is_system    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 2: Seed all 22 known roles
-- Sources: RoleType.java enum (17) + frontend-only (CRM_VIEWER, MANAGER) + @RolesAllowed-only (CXO, FINANCE, VTV)
-- System roles (SYSTEM, APPLICATION) are internal-only and cannot be deleted/renamed
INSERT INTO role_definition (name, display_label, is_system) VALUES
    ('SYSTEM',         'System',            TRUE),
    ('APPLICATION',    'Application',       TRUE),
    ('USER',           'User',              FALSE),
    ('EXTERNAL',       'External',          FALSE),
    ('ADMIN',          'Admin',             FALSE),
    ('MANAGER',        'Manager',           FALSE),
    ('PARTNER',        'Partner',           FALSE),
    ('TECHPARTNER',    'Tech Partner',      FALSE),
    ('CYBERPARTNER',   'Cyber Partner',     FALSE),
    ('TEAMLEAD',       'Team Lead',         FALSE),
    ('SALES',          'Sales',             FALSE),
    ('CRM_VIEWER',     'CRM Viewer',        FALSE),
    ('ACCOUNTING',     'Accounting',        FALSE),
    ('MARKETING',      'Marketing',         FALSE),
    ('EDITOR',         'Editor',            FALSE),
    ('HR',             'HR',                FALSE),
    ('DPO',            'DPO',               FALSE),
    ('COMMUNICATIONS', 'Communications',    FALSE),
    ('TEMP',           'Temporary',         FALSE),
    ('CXO',            'CXO',               FALSE),
    ('FINANCE',        'Finance',           FALSE),
    ('VTV',            'VTV',               FALSE);

-- Step 3: Widen roles.role from VARCHAR(16) to VARCHAR(50)
-- This is a non-destructive, online-compatible change
ALTER TABLE roles MODIFY COLUMN role VARCHAR(50) NOT NULL;

-- Step 4: Add foreign key constraint
-- ON UPDATE CASCADE: if a role_definition.name is renamed, all assignments update
-- ON DELETE RESTRICT: cannot delete a role_definition that has assignments
ALTER TABLE roles
    ADD CONSTRAINT fk_roles_role_definition
    FOREIGN KEY (role) REFERENCES role_definition (name)
    ON UPDATE CASCADE
    ON DELETE RESTRICT;
