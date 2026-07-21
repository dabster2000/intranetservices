-- ====================================================================
-- V418: Practice registry + team-scoped settings (Part 1, Release 1).
--
-- Makes practice existence, naming, leadership and team grouping DATA
-- (a registry table) while the legacy storage codes (PM/BA/SA/DEV/CYB)
-- remain the universal key. Also re-homes the IT budget from practice
-- level to team level so the later JK->UD flip is budget-neutral.
--
-- Spec: docs/superpowers/specs/2026-07-19-practice-data-model-design.md §3.2
--
-- Conventions (per project rules):
--   * All DDL is idempotent (CREATE TABLE / ADD COLUMN / ADD CONSTRAINT
--     / ADD KEY guarded with IF NOT EXISTS).
--   * New tables declare utf8mb4 / utf8mb4_general_ci so their FK columns
--     match the pre-Flyway `team` table's charset/collation.
--   * Seeds use INSERT IGNORE so re-runs are no-ops.
--   * page_registry INSERT mirrors V349/V374/V414 (ON DUPLICATE KEY UPDATE).
-- ====================================================================

-- 1) Practice registry: a practice is now a row. -----------------------
--    `code` is the legacy STORAGE key (what user.practice, history, facts
--    and the engine store) — immutable in Part 1, retired in Part 2.
--    `display_code` is what humans see (PM / IA / BU / TECH / CYB).
CREATE TABLE IF NOT EXISTS practice (
    code          VARCHAR(10)  NOT NULL PRIMARY KEY,
    display_code  VARCHAR(10)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    type          ENUM('PRACTICE','SEGMENT') NOT NULL DEFAULT 'PRACTICE',
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order    INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_practice_display_code (display_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Seed the registry. The five core PRACTICE rows carry the decision-7
-- display renames (SA->IA, BA->BU, DEV->TECH); UD is the "no practice"
-- SEGMENT sentinel; JK is a transitional inactive SEGMENT retired in V418.
INSERT IGNORE INTO practice (code, display_code, name, type, active, sort_order) VALUES
    ('PM',  'PM',   'Project Managers',                    'PRACTICE', 1, 10),
    ('SA',  'IA',   'IT Architecture',                     'PRACTICE', 1, 20),
    ('BA',  'BU',   'Business & Users',                    'PRACTICE', 1, 30),
    ('DEV', 'TECH', 'Technology',                          'PRACTICE', 1, 40),
    ('CYB', 'CYB',  'Cyber Security',                      'PRACTICE', 1, 50),
    ('UD',  'UD',   'No practice',                         'SEGMENT',  1, 90),
    ('JK',  'JK',   'Junior Consultants (transitional)',   'SEGMENT',  0, 99);

-- 2) Practice leads: temporal, multi-lead, mirrors the teamroles idiom. -
CREATE TABLE IF NOT EXISTS practice_lead (
    uuid          VARCHAR(36) NOT NULL PRIMARY KEY,
    practice_code VARCHAR(10) NOT NULL,
    useruuid      VARCHAR(36) NOT NULL,
    startdate     DATE        NOT NULL,
    enddate       DATE        NULL,
    CONSTRAINT fk_practice_lead_practice FOREIGN KEY (practice_code) REFERENCES practice (code),
    KEY idx_practice_lead_practice (practice_code, startdate),
    KEY idx_practice_lead_user (useruuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 3) The missing team -> practice edge. --------------------------------
--    Split into column + constraint so a partial re-run stays idempotent.
ALTER TABLE team
    ADD COLUMN IF NOT EXISTS practice_code VARCHAR(10) NULL;
ALTER TABLE team
    ADD CONSTRAINT fk_team_practice FOREIGN KEY IF NOT EXISTS (practice_code) REFERENCES practice (code);

-- Backfill by verified production UUID (2026-07-19). Six teams stay NULL
-- (ARBA, Junior Team, Team Partner, Team Stab, Management, Teamleads).
-- Re-running these UPDATEs is inherently idempotent.
UPDATE team SET practice_code = 'SA'  WHERE uuid = '48b5c8d0-a56b-45b8-92db-ba1e09fd8222'; -- Team ACE
UPDATE team SET practice_code = 'SA'  WHERE uuid = 'db41a2b9-bc0b-454d-85e6-8a97aa147de4'; -- Team Aspire
UPDATE team SET practice_code = 'BA'  WHERE uuid = '1a0a6503-a277-4bf4-a71b-ef40d3480ab5'; -- Team Y
UPDATE team SET practice_code = 'BA'  WHERE uuid = '0a45209d-5286-48ee-9af1-674c9fe293a9'; -- Team Really Bad Ass
UPDATE team SET practice_code = 'PM'  WHERE uuid = '054e7310-1761-4b18-8ea3-ec7bac5364cd'; -- Team Puppet Masters
UPDATE team SET practice_code = 'PM'  WHERE uuid = '2fea1fd5-a9f1-4262-817e-908e6f20ca22'; -- Team it
UPDATE team SET practice_code = 'DEV' WHERE uuid = 'd2d2ff35-9c16-47c9-a049-4f34c049faba'; -- Team HiTech
UPDATE team SET practice_code = 'DEV' WHERE uuid = '3ed48ed5-0e45-49f9-81c2-5bcec06a7950'; -- Team Tech it or Leave it
UPDATE team SET practice_code = 'CYB' WHERE uuid = '4ac240cb-a007-4918-82df-eb02cf757582'; -- Team Cyber Security

-- 4) IT budget moves to team level (decision 5). ----------------------
--    Mirrors the practice_settings key/value shape so future team-scoped
--    settings have a home.
CREATE TABLE IF NOT EXISTS team_settings (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    teamuuid      VARCHAR(36)  NOT NULL,
    setting_key   VARCHAR(50)  NOT NULL,
    setting_value VARCHAR(255) NOT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by    VARCHAR(36)  NULL,
    UNIQUE KEY uq_team_setting (teamuuid, setting_key),
    CONSTRAINT fk_team_settings_team FOREIGN KEY (teamuuid) REFERENCES team (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Seed it_budget preserving today's per-person outcomes:
--   Team HiTech, Team Tech it or Leave it -> 32000  (today's DEV budget)
--   Junior Team                           -> 0      (today's JK budget)
--   every other team                      -> 25000
-- INSERT IGNORE + uq_team_setting makes the seed idempotent.
INSERT IGNORE INTO team_settings (teamuuid, setting_key, setting_value, updated_by)
SELECT t.uuid,
       'it_budget',
       CASE
           WHEN t.uuid IN ('d2d2ff35-9c16-47c9-a049-4f34c049faba',
                           '3ed48ed5-0e45-49f9-81c2-5bcec06a7950') THEN '32000'
           WHEN t.uuid = '28b19c16-cd19-4ffb-bca1-e6245448f428'    THEN '0'
           ELSE '25000'
       END,
       'V418_MIGRATION'
FROM team t;

-- 5) Drop the misleading 'PM' default on user.practice. ---------------
--    A raw insert must not silently mint a Project Management consultant;
--    'UD' (no practice) is the honest fallback and fits varchar(3).
ALTER TABLE `user` ALTER COLUMN practice SET DEFAULT 'UD';

-- 6) Settings-tab registration (mirrors V414). ------------------------
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('settings-practices', 'Practices', 1, '/settings?tab=practices', 'ADMIN', 130, 'SETTINGS', 'Users', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    is_visible     = VALUES(is_visible),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    is_external    = VALUES(is_external),
    external_url   = VALUES(external_url);
