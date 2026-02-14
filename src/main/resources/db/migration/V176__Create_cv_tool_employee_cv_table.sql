-- ============================================================================
-- V176: Create cv_tool_employee_cv table
-- ============================================================================
-- Purpose:  Store base CV data fetched from the external CV Tool API.
--           The full CV (educations, competencies, certifications, languages,
--           projects) is stored as a JSON blob for display purposes.
-- Sync:     Populated nightly by CvToolSyncBatchlet (04:00).
-- Source:   CV Tool API - GET /cv/employee/{id}
-- ============================================================================

CREATE TABLE cv_tool_employee_cv (
    uuid                VARCHAR(36)  NOT NULL PRIMARY KEY,
    useruuid            VARCHAR(36)  NOT NULL COMMENT 'FK to our User table',
    cvtool_employee_id  INT          NOT NULL COMMENT 'CV Tool internal employee ID',
    cvtool_cv_id        INT          NOT NULL COMMENT 'CV Tool internal CV ID',
    employee_name       VARCHAR(255) NULL     COMMENT 'Name from CV Tool',
    employee_title      VARCHAR(500) NULL     COMMENT 'e.g. Senior Consultant',
    employee_profile    TEXT         NULL     COMMENT 'Profile summary text',
    cv_data_json        LONGTEXT     NOT NULL COMMENT 'Full CV as JSON (educations, competencies, etc.)',
    cv_language         INT          DEFAULT 1 COMMENT '1=Danish, 2=English',
    last_synced_at      DATETIME     NOT NULL COMMENT 'When we last synced this record',
    cv_last_updated_at  DATETIME     NULL     COMMENT 'Last_Updated_At from CV Tool API',
    UNIQUE KEY uk_useruuid (useruuid),
    UNIQUE KEY uk_cvtool_employee (cvtool_employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
