-- ============================================================================
-- V247: Create bug report tables
-- ============================================================================
-- Purpose: Introduces the bug report system schema. Three tables: the main
--          bug_reports table (aggregate root), bug_report_comments (child),
--          and bug_report_notifications (child). Supports the full lifecycle
--          from DRAFT through CLOSED, with AI-generated fields, screenshot
--          references, CloudWatch log excerpts, and in-app notifications.
--
-- Spec reference: docs/specs/bug-report-system.md, Section 4.2
--
-- Changes:
--   1. Creates `bug_reports` table (aggregate root, full report data)
--   2. Creates `bug_report_comments` table (comments + system messages)
--   3. Creates `bug_report_notifications` table (in-app notifications)
--   4. Adds 5 indexes for common query patterns
--
-- Backwards compatibility:
--   - No existing tables are modified
--   - No existing data is affected
--   - New tables only; purely additive migration
--
-- Data semantics:
--   - Timezone: created_at and updated_at store server-local time via
--     CURRENT_TIMESTAMP (MariaDB default). The Quarkus application layer
--     should treat these as UTC-consistent with the server timezone.
--   - Nullability: Most text fields are nullable because the DRAFT record
--     is created before AI analysis completes. Only reporter_uuid, status,
--     severity, and timestamps are NOT NULL on creation.
--   - Soft vs hard delete: DRAFT reports are HARD DELETED (ON DELETE CASCADE
--     propagates to comments and notifications). Non-draft reports follow
--     the status lifecycle to CLOSED; they are never deleted except by the
--     scheduled retention cleanup job (>12 months after CLOSED).
--   - ENUM values: status and severity are database-enforced ENUMs.
--     Adding a new value requires a migration (ALTER TABLE ... MODIFY COLUMN).
--   - log_excerpt uses MEDIUMTEXT (up to 16MB) to accommodate verbose log
--     output; the application layer caps retrieval at 500KB.
--   - console_errors stores a JSON array as TEXT (not JSON type) for
--     compatibility with Hibernate string mapping.
--
-- Rollback strategy:
--   DROP TABLE IF EXISTS bug_report_notifications;
--   DROP TABLE IF EXISTS bug_report_comments;
--   DROP TABLE IF EXISTS bug_reports;
--
-- Impact:
--   - New Quarkus entities needed: BugReport.java, BugReportComment.java,
--     BugReportNotification.java
--   - New service: BugReportService.java
--   - New REST resource: BugReportResource.java
--   - No existing entities or repositories are affected
--
-- Author: Claude Code
-- Date: 2026-03-15
-- ============================================================================

-- Step 1: Create the bug_reports table (aggregate root)
-- Stores the full bug report including AI-generated fields, browser context,
-- screenshot S3 reference, and CloudWatch log excerpt. UUID primary key is
-- consistent with the existing codebase pattern (VARCHAR(36)).
CREATE TABLE bug_reports (
    uuid                VARCHAR(36)   NOT NULL,
    reporter_uuid       VARCHAR(36)   NOT NULL,
    assignee_uuid       VARCHAR(36)   NULL,
    status              ENUM('DRAFT','SUBMITTED','IN_PROGRESS','RESOLVED','CLOSED')
                                      NOT NULL DEFAULT 'DRAFT',
    title               VARCHAR(500)  NULL,
    description         TEXT          NULL,
    steps_to_reproduce  TEXT          NULL,
    expected_behavior   TEXT          NULL,
    actual_behavior     TEXT          NULL,
    severity            ENUM('LOW','MEDIUM','HIGH','CRITICAL')
                                      NOT NULL DEFAULT 'MEDIUM',
    screenshot_s3_key   VARCHAR(512)  NULL,
    log_excerpt         MEDIUMTEXT    NULL,
    page_url            VARCHAR(2000) NULL,
    user_agent          VARCHAR(1000) NULL,
    viewport_width      INT           NULL,
    viewport_height     INT           NULL,
    console_errors      TEXT          NULL,
    user_roles          VARCHAR(500)  NULL,
    ai_raw_response     TEXT          NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),

    CONSTRAINT fk_bug_reports_reporter
        FOREIGN KEY (reporter_uuid) REFERENCES user (uuid),

    CONSTRAINT fk_bug_reports_assignee
        FOREIGN KEY (assignee_uuid) REFERENCES user (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 2: Create the bug_report_comments table
-- Supports both user comments and system-generated status change messages
-- (is_system = 1). ON DELETE CASCADE ensures comments are removed when a
-- DRAFT report is hard-deleted.
CREATE TABLE bug_report_comments (
    uuid          VARCHAR(36) NOT NULL,
    report_uuid   VARCHAR(36) NOT NULL,
    author_uuid   VARCHAR(36) NOT NULL,
    content       TEXT        NOT NULL,
    is_system     TINYINT(1)  NOT NULL DEFAULT 0,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),

    CONSTRAINT fk_bug_report_comments_report
        FOREIGN KEY (report_uuid) REFERENCES bug_reports (uuid)
        ON DELETE CASCADE,

    CONSTRAINT fk_bug_report_comments_author
        FOREIGN KEY (author_uuid) REFERENCES user (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 3: Create the bug_report_notifications table
-- In-app notification queue. Polling-based (60s interval from frontend).
-- ON DELETE CASCADE ensures notifications are removed when a DRAFT report
-- is hard-deleted. The (user_uuid, is_read) composite index supports the
-- common "unread notifications for user" query pattern.
CREATE TABLE bug_report_notifications (
    uuid          VARCHAR(36)  NOT NULL,
    user_uuid     VARCHAR(36)  NOT NULL,
    report_uuid   VARCHAR(36)  NOT NULL,
    type          ENUM('STATUS_CHANGED','COMMENT_ADDED','ASSIGNED')
                               NOT NULL,
    message       VARCHAR(500) NOT NULL,
    is_read       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (uuid),

    CONSTRAINT fk_bug_report_notifications_user
        FOREIGN KEY (user_uuid) REFERENCES user (uuid),

    CONSTRAINT fk_bug_report_notifications_report
        FOREIGN KEY (report_uuid) REFERENCES bug_reports (uuid)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Step 4: Create indexes for common query patterns
-- idx_bug_reports_reporter: List reports by reporter (user's own reports page)
CREATE INDEX idx_bug_reports_reporter
    ON bug_reports (reporter_uuid);

-- idx_bug_reports_assignee: Filter reports by assignee (admin triage view)
CREATE INDEX idx_bug_reports_assignee
    ON bug_reports (assignee_uuid);

-- idx_bug_reports_status: Filter reports by status (admin list, cleanup job)
CREATE INDEX idx_bug_reports_status
    ON bug_reports (status);

-- idx_bug_report_comments_report: List comments for a specific report
CREATE INDEX idx_bug_report_comments_report
    ON bug_report_comments (report_uuid);

-- idx_notifications_user_unread: Unread notification count + list per user
-- Composite index on (user_uuid, is_read) covers the WHERE clause for
-- "SELECT ... WHERE user_uuid = ? AND is_read = 0" without a table scan.
CREATE INDEX idx_notifications_user_unread
    ON bug_report_notifications (user_uuid, is_read);
