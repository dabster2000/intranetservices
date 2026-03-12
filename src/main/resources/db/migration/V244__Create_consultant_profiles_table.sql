-- ============================================================================
-- V244: Create consultant_profiles cache table
-- ============================================================================
-- Purpose: Stores AI-generated consultant sales pitches, industries, and top
--          skills. Acts as a cache layer: profiles are regenerated when stale
--          (older than 7 days or when CV data has changed).
--
-- Columns:
--   useruuid       - FK to user(uuid), one profile per consultant
--   pitch_text     - AI-generated 2-sentence sales pitch (nullable until generated)
--   industries_json - JSON array of industry strings, e.g. ["Finance","Healthcare"]
--   top_skills_json - JSON array of skill strings, e.g. ["Java","AWS","Kubernetes"]
--   generated_at   - Timestamp when the AI last generated this profile (UTC)
--   cv_updated_at  - Timestamp of the CV data used for generation, used for
--                    staleness detection (compared against current CV updated_at)
--
-- Nullability: All columns except useruuid are nullable. A row with all NULLs
--              means the profile has not yet been generated (or generation failed).
--
-- Timezone: generated_at and cv_updated_at store UTC values. The application
--           layer (Quarkus) is responsible for ensuring UTC consistency.
--
-- Rollback: DROP TABLE IF EXISTS consultant_profiles;
--
-- Impact:
--   - New Quarkus entity: ConsultantProfile (to be created by backend task)
--   - No existing tables are modified
--   - No existing entities or repositories are affected
--
-- Author: Claude Code
-- Date: 2026-03-12
-- ============================================================================

CREATE TABLE IF NOT EXISTS consultant_profiles (
    useruuid        VARCHAR(36)  NOT NULL,
    pitch_text      TEXT         NULL,
    industries_json JSON         NULL,
    top_skills_json JSON         NULL,
    generated_at    DATETIME     NULL,
    cv_updated_at   DATETIME     NULL,

    PRIMARY KEY (useruuid),
    CONSTRAINT fk_consultant_profiles_user FOREIGN KEY (useruuid) REFERENCES user(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
