-- ============================================================================
-- V177: Create user_career_level table for temporal career model
-- ============================================================================
-- Replaces the simple integer primary_skill_level (1-5) on the user table
-- with a temporal career level model following the same pattern as
-- userstatus and salary tables.
--
-- The Trustworks career model has 4 tracks + entry + partner level:
--   Entry: JUNIOR_CONSULTANT
--   Delivery: CONSULTANT, PROFESSIONAL_CONSULTANT, SENIOR_CONSULTANT
--   Advisory: LEAD_CONSULTANT, MANAGING_CONSULTANT, PRINCIPAL_CONSULTANT
--   Leadership: ASSOCIATE_MANAGER, MANAGER, SENIOR_MANAGER
--   Client Engagement: ENGAGEMENT_MANAGER, SENIOR_ENGAGEMENT_MANAGER, ENGAGEMENT_DIRECTOR
--   Partner: ASSOCIATE_PARTNER, PARTNER, THOUGHT_LEADER_PARTNER, PRACTICE_LEADER, DIRECTOR
-- ============================================================================

CREATE TABLE user_career_level (
    uuid         VARCHAR(36)  NOT NULL,
    useruuid     VARCHAR(36)  NOT NULL,
    active_from  DATE         NOT NULL,
    career_track VARCHAR(30)  NOT NULL,
    career_level VARCHAR(40)  NOT NULL,
    PRIMARY KEY (uuid),
    CONSTRAINT fk_ucl_user FOREIGN KEY (useruuid) REFERENCES user(uuid)
);

CREATE INDEX idx_ucl_useruuid ON user_career_level (useruuid);
CREATE INDEX idx_ucl_useruuid_active_from ON user_career_level (useruuid, active_from DESC);

-- ============================================================================
-- Migrate existing primary_skill_level values to user_career_level records.
-- Mapping:
--   1 -> JUNIOR_CONSULTANT (no track / DELIVERY)
--   2 -> CONSULTANT (DELIVERY)
--   3 -> PROFESSIONAL_CONSULTANT (DELIVERY)
--   4 -> SENIOR_CONSULTANT (DELIVERY)
--   5 -> LEAD_CONSULTANT (ADVISORY)
--
-- Uses the user's created date as the active_from date for the initial record.
-- Only migrates users that have a primary_skill_level set (> 0).
-- ============================================================================

INSERT INTO user_career_level (uuid, useruuid, active_from, career_track, career_level)
SELECT
    UUID() AS uuid,
    u.uuid AS useruuid,
    u.created AS active_from,
    CASE u.primary_skill_level
        WHEN 1 THEN 'DELIVERY'
        WHEN 2 THEN 'DELIVERY'
        WHEN 3 THEN 'DELIVERY'
        WHEN 4 THEN 'DELIVERY'
        WHEN 5 THEN 'ADVISORY'
        ELSE 'DELIVERY'
    END AS career_track,
    CASE u.primary_skill_level
        WHEN 1 THEN 'JUNIOR_CONSULTANT'
        WHEN 2 THEN 'CONSULTANT'
        WHEN 3 THEN 'PROFESSIONAL_CONSULTANT'
        WHEN 4 THEN 'SENIOR_CONSULTANT'
        WHEN 5 THEN 'LEAD_CONSULTANT'
        ELSE 'JUNIOR_CONSULTANT'
    END AS career_level
FROM user u
WHERE u.primary_skill_level > 0;
