-- =============================================================================
-- Migration V217: Create user_personal_details table
--
-- Purpose:
--   Extracts personal/HR details from the user table into a dedicated temporal
--   table. This follows the same temporal pattern used by user_career_level,
--   user_contactinfo (V216), salary, etc.
--
--   Fields migrated from user:
--     pension, healthcare, pensiondetails, photoconsent, defects, other
--
--   These fields remain on the user table for backward compatibility until
--   all consumers are migrated. A future migration will drop them from user.
--
-- Table grain:
--   (useruuid, active_date) — one record per user per effective date.
--   Point-in-time lookup: SELECT ... WHERE useruuid = ? AND active_date <= ?
--   ORDER BY active_date DESC LIMIT 1
--
-- Audit columns:
--   created_at, updated_at, created_by, modified_by — follow V88 pattern.
--   Note: V218 adds these same audit columns to 8 OTHER temporal tables.
--   This table gets them here at creation time (preferred over ALTER later).
--
-- Data types:
--   uuid          VARCHAR(36) — UUIDv4, consistent with all other PK columns
--   useruuid      VARCHAR(36) — FK to user.uuid
--   active_date   DATE        — temporal versioning (not nullable)
--   pension       BOOLEAN     — whether employee has pension (default false)
--   healthcare    BOOLEAN     — whether employee has healthcare (default false)
--   pensiondetails TEXT       — free-text pension notes (nullable)
--   photoconsent  BOOLEAN     — photo consent given (default false)
--   defects       TEXT        — free-text medical/physical notes (nullable)
--   other         TEXT        — free-text general notes (nullable)
--   created_at    DATETIME(6) — microsecond precision, consistent with V88
--   updated_at    DATETIME(6)
--   created_by    VARCHAR(255) NOT NULL — typically user UUID from X-Requested-By
--   modified_by   VARCHAR(255) NULL     — nullable per V88 convention
--
-- Rollback strategy:
--   DROP TABLE IF EXISTS user_personal_details;
--   No data is lost (source columns remain on user table).
--
-- Impact assessment:
--   Quarkus: create UserPersonalDetails.java entity + UserPersonalDetailsRepository
--   No existing queries break (additive migration only)
-- =============================================================================

-- Step 1: Create the table
CREATE TABLE user_personal_details (
    uuid            VARCHAR(36)  NOT NULL,
    useruuid        VARCHAR(36)  NOT NULL    COMMENT 'FK to user.uuid',
    active_date     DATE         NOT NULL    COMMENT 'Effective date of this personal details record',
    pension         BOOLEAN      NOT NULL DEFAULT false   COMMENT 'Employee has employer pension arrangement',
    healthcare      BOOLEAN      NOT NULL DEFAULT false   COMMENT 'Employee has private healthcare insurance',
    pensiondetails  TEXT         NULL        COMMENT 'Free-text notes on pension arrangement',
    photoconsent    BOOLEAN      NOT NULL DEFAULT false   COMMENT 'Employee consented to photo publication',
    defects         TEXT         NULL        COMMENT 'Free-text health/physical notes (HR use only)',
    other           TEXT         NULL        COMMENT 'Free-text general HR notes',
    created_at      DATETIME(6)  NOT NULL    COMMENT 'Creation timestamp',
    updated_at      DATETIME(6)  NOT NULL    COMMENT 'Last update timestamp',
    created_by      VARCHAR(255) NOT NULL    COMMENT 'User identifier who created the record',
    modified_by     VARCHAR(255) NULL        COMMENT 'User identifier who last modified the record',

    CONSTRAINT pk_user_personal_details PRIMARY KEY (uuid),
    CONSTRAINT fk_upd_user FOREIGN KEY (useruuid) REFERENCES user(uuid)
);

-- Step 2: Index for temporal point-in-time lookup
CREATE INDEX idx_upd_user_date ON user_personal_details(useruuid, active_date DESC);

-- Step 3: Migrate existing data from user table
--   Creates one baseline record per user (active from 2020-01-01).
--   UUID() generates a fresh UUIDv4 for each row.
--   Null-safety: COALESCE handles users where BOOLEAN columns are NULL in source.
INSERT INTO user_personal_details
    (uuid, useruuid, active_date, pension, healthcare, pensiondetails,
     photoconsent, defects, other, created_at, updated_at, created_by, modified_by)
SELECT
    UUID()                          AS uuid,
    u.uuid                          AS useruuid,
    '2020-01-01'                    AS active_date,
    COALESCE(u.pension, false)      AS pension,
    COALESCE(u.healthcare, false)   AS healthcare,
    u.pensiondetails                AS pensiondetails,
    COALESCE(u.photoconsent, false) AS photoconsent,
    u.defects                       AS defects,
    u.other                         AS other,
    NOW()                           AS created_at,
    NOW()                           AS updated_at,
    'system'                        AS created_by,
    'system'                        AS modified_by
FROM user u;
