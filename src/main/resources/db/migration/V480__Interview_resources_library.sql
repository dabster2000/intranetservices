-- V480: Interview resources library (shared interview guides, cases,
-- assessment templates) + per-position pins.
--
-- Files live in the trustworksfiles S3 bucket via the files table
-- (relateduuid = interview_resources.uuid), mirroring every other
-- recruitment file. Rows are soft-deleted (active = 0) so download links
-- in old Slack messages / notes fail gracefully rather than 404 forever.

CREATE TABLE interview_resources (
    uuid              VARCHAR(36)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    category          VARCHAR(30)  NOT NULL
        COMMENT 'INTERVIEW_GUIDE | CASE_MATERIAL | ASSESSMENT_TEMPLATE | OTHER',
    description       TEXT         NULL,
    file_uuid         VARCHAR(36)  NOT NULL
        COMMENT 'files.uuid (S3 object, relateduuid = this row)',
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    file_size         BIGINT       NOT NULL,
    active            TINYINT(1)   NOT NULL DEFAULT 1,

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL COMMENT 'users.uuid from X-Requested-By',

    PRIMARY KEY (uuid),
    CONSTRAINT chk_ires_category_enum
        CHECK (category IN ('INTERVIEW_GUIDE','CASE_MATERIAL','ASSESSMENT_TEMPLATE','OTHER')),
    KEY idx_ires_active_category (active, category)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Shared interview materials (guides, cases, assessment templates)';

CREATE TABLE interview_resource_pins (
    uuid          VARCHAR(36) NOT NULL,
    resource_uuid VARCHAR(36) NOT NULL,
    position_uuid VARCHAR(36) NOT NULL,

    created_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL COMMENT 'users.uuid from X-Requested-By',

    PRIMARY KEY (uuid),
    UNIQUE KEY uq_irpin_resource_position (resource_uuid, position_uuid),
    KEY idx_irpin_position (position_uuid),
    CONSTRAINT fk_irpin_resource
        FOREIGN KEY (resource_uuid) REFERENCES interview_resources (uuid) ON DELETE CASCADE,
    CONSTRAINT fk_irpin_position
        FOREIGN KEY (position_uuid) REFERENCES recruitment_positions (uuid) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='Interview resources pinned to a position (shown to its interviewers)';
