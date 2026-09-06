-- V575: user_competence_tag — JK Team 2.0 WP6 §4.6.3 (competence tags)
--
-- MANUAL tags are entered by the team lead as free text (spec §2.1.1 is explicit that
-- this is not a controlled list). CV_TOOL tags are the `competencies[].title` entries
-- of the CV JSON that V176 stores in cv_tool_employee_cv, refreshed by the nightly CV
-- Tool sync; they are suggestions — additive, overridable, and always shown with their
-- source. Unique on (useruuid, tag); the case-insensitive collation makes "Java" and
-- "java" the same tag.

CREATE TABLE IF NOT EXISTS user_competence_tag (
    useruuid   VARCHAR(36) NOT NULL,
    tag        VARCHAR(64) NOT NULL,
    source     ENUM('MANUAL', 'CV_TOOL') NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(36) NULL,
    PRIMARY KEY (useruuid, tag),
    INDEX idx_user_competence_tag_source (useruuid, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
