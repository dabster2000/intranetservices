-- ============================================================================
-- V591: Sectors and GTM teams — the sector lead, which segments a GTM team
--       covers, and the client's own account-team bubble
-- ============================================================================
-- Feature: docs/specs/intra-crm-sectors-gtm-teams-2026-09-13.md §4 (V590 in that
--          spec; renumbered because V590 was taken by client notes on staging).
-- Domain:  aggregates/crm/sector + aggregates/crm/gtm
--
-- WHAT THIS IS
--   The CRM spec (§4.4) puts Sectors and GTM teams on the accounts page and gives every
--   sector a lead. Three things had no column:
--
--   1. WHO leads a sector. A temporal table, the practice_lead idiom (V418): several
--      rows over time, `enddate IS NULL` is the current one. The spec's open question 4
--      ("a person, or the GTM bubble owner?") was settled on 2026-09-13 as a person.
--   2. WHICH SEGMENTS a GTM team covers. A GTM team is a FOCUS bubble (Offentlig
--      Digitalisering, Grøn Omstilling, ...), and one team can span two segments, so
--      this is a link table rather than a column on `bubbles`.
--   3. The client's OWN account-team bubble. `client_account.gtm_bubble_uuid` (V585)
--      was documented as "the Account Team bubble", but in the data the ACCOUNT_TEAM
--      bubbles are per client (Ørsted, Novo Nordisk, Rambøll, ...) while the FOCUS
--      bubbles are the go-to-market teams. From this migration on `gtm_bubble_uuid`
--      points at a FOCUS bubble (AccountService refuses anything else) and the client's
--      own team gets its own column.
--
-- SEGMENT IS THE ENUM, NOT A REGISTRY
--   `client.segment` (PUBLIC, HEALTH, FINANCIAL, ENERGY, EDUCATION, OTHER) stays the
--   sector key: the executive dashboard already reports on it and 272 clients carry it.
--   The ENUM below mirrors the column on `client` exactly; adding a sector is a
--   migration on both, on purpose.
--
-- SEEDS
--   gtm_team_sector is seeded for the four active FOCUS bubbles by their exact name —
--   the mapping is unambiguous (one bubble per sector today) and INSERT IGNORE keeps a
--   later hand edit from being undone by a re-run. NO sector_lead is seeded: the
--   prototype hard-coded one per segment, but nobody has decided, and a seeded lead
--   would read as a decision somebody took. NO account_team_bubble_uuid is seeded:
--   only 6 of 14 ACCOUNT_TEAM bubble names match a client name, so a person links it.
--
-- NO FOREIGN KEYS onto client / user / bubbles, matching V585 and V586.
--
-- RESERVED-WORD CHECK
--   `segment`, `startdate`, `enddate`, `bubble_uuid` are not reserved. `current` and
--   `rank` are avoided (V586). `LINES` is the one that took the staging canary down
--   on 2026-08-26 (V534).
--
-- COLLATION: utf8mb4_general_ci — these join `client`, `user` and `bubbles`.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, INSERT IGNORE.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   ALTER TABLE client_account DROP COLUMN account_team_bubble_uuid;
--   DROP TABLE gtm_team_sector, sector_lead;
--   No permission rows: sectors and GTM teams are gated by accounts:read / accounts:write.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Who leads a sector — temporal, one current row per segment
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sector_lead
(
    uuid        CHAR(36) NOT NULL,
    segment     ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    user_uuid   CHAR(36) NOT NULL,
    startdate   DATE     NOT NULL,
    enddate     DATE     NULL COMMENT 'NULL = current (practice_lead idiom)',
    created_at  DATETIME NOT NULL,
    created_by  CHAR(36) NOT NULL,
    modified_at DATETIME NOT NULL,
    modified_by CHAR(36) NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_sector_lead_segment (segment, startdate),
    KEY idx_sector_lead_user (user_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='The sector lead over time; enddate NULL is the current one (sectors spec 4)';

-- ----------------------------------------------------------------------------
-- 2. Which segments a GTM team (a FOCUS bubble) covers
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gtm_team_sector
(
    uuid        CHAR(36) NOT NULL,
    bubble_uuid CHAR(36) NOT NULL COMMENT 'bubbles.uuid, type FOCUS — soft reference',
    segment     ENUM ('PUBLIC','HEALTH','FINANCIAL','ENERGY','EDUCATION','OTHER') NOT NULL,
    created_at  DATETIME NOT NULL,
    created_by  CHAR(36) NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_gtm_team_sector (bubble_uuid, segment),
    KEY idx_gtm_team_sector_segment (segment)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='A GTM team covers one or more sectors (sectors spec 4)';

-- ----------------------------------------------------------------------------
-- 3. The client's own ACCOUNT_TEAM bubble — distinct from the GTM team
-- ----------------------------------------------------------------------------
ALTER TABLE client_account
    ADD COLUMN IF NOT EXISTS account_team_bubble_uuid CHAR(36) NULL
        COMMENT 'The client''s own ACCOUNT_TEAM bubble (bubbles.uuid); gtm_bubble_uuid is the FOCUS bubble'
        AFTER gtm_bubble_uuid;

-- ----------------------------------------------------------------------------
-- 4. Seed the four GTM teams that exist today onto their sector, by exact name
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO gtm_team_sector (uuid, bubble_uuid, segment, created_at, created_by)
SELECT UUID(), b.uuid, m.segment, NOW(), 'V591'
FROM bubbles b
         JOIN (SELECT 'Offentlig Digitalisering' AS name, 'PUBLIC' AS segment
               UNION ALL SELECT 'Grøn Omstilling', 'ENERGY'
               UNION ALL SELECT 'Fremtidens Finansielle Sektor', 'FINANCIAL'
               UNION ALL SELECT 'Pharma & Life Science', 'HEALTH') AS m
              ON TRIM(b.name) = m.name
WHERE b.type = 'FOCUS'
  AND b.active = 1;
