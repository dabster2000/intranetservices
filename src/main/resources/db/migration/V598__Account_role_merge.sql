-- ============================================================================
-- V598 — one store for the people on an account
--
-- Spec: docs/specs/intra-crm-account-people-merge-2026-09-14.md §2
--
-- WHAT THIS DOES
--   1. Snapshots client_account_role and the ACCOUNT_TEAM bubble memberships, because
--      steps 3 and 4 are not reversible by a down-migration.
--   2. Widens client_account_role.role with MEMBER.
--   3. Moves ACCOUNT_TEAM bubble membership into MEMBER rows keyed by client_uuid.
--   4. Deletes the RESPONSIBLE rows and narrows the enum to ('SUPPORTED_BY','MEMBER').
--
-- WHY THE MOVE AND NOT A DERIVATION
--   Where the bubbles are populated they are a CURATED SUBSET of who has ever been
--   staffed at the client (12 of 23 at Nordisk Ministerråd, 5 of 38 at
--   Digitaliseringsstyrelsen, 3 of 18 at Ørsted). That curation is real information,
--   so the rows are moved; membership is never recomputed from staffing.
--
-- WHY RESPONSIBLE GOES
--   It was write-only. AccountService.syncResponsibleRole() deleted and rewrote it on
--   every write path and nothing in main, test or the frontend ever read it — which is
--   why all 9 rows agreed with client.accountmanager, and why dropping it changes no
--   behaviour. client.accountmanager is the single owner from here on.
--
-- HOW A BUBBLE FINDS ITS CLIENT — three sources, in this priority
--   1 the hand-made link client_account.account_team_bubble_uuid (dropped in V599)
--   2 the bubble name, trimmed: four bubble names carry a trailing \r
--   3 the five pairs Hans confirmed on 2026-09-14 (spec §5). 'Forsvaret' and 'Rambøll'
--     were genuinely ambiguous and he picked; the other three had one candidate each.
--     'Forsvaret' was changed to Forsvarskommandoen on the same day, after a run against
--     the local staging copy showed the first pick — Forsvarsministeriets Materiel- og
--     Indkøbsstyrelse — had 0 contracts and nobody who had ever booked an hour on it,
--     while Forsvarskommandoen had both. The bubble formed around work; put the people
--     where the work is.
--   The priority matters: a bubble that already carries a hand-made link must not also
--   seed members onto a different client that happens to share its name.
--
-- TERMINATED MEMBERS ARE NOT MIGRATED (Hans, 2026-09-14 — spec §9 Q2 answered the
--   other way round from the recommendation). 4 of the 62 memberships belong to people
--   whose latest userstatus row is TERMINATED. bubble_members is never deleted by this
--   migration, so those four rows survive in place and can be re-added by hand.
--
-- GUARDS
--   · a person is never listed twice on one account (the unique key, plus NOT EXISTS
--     so an existing SUPPORTED_BY row is not shadowed by a MEMBER row)
--   · the account's owner is never also a member
--   · INSERT IGNORE, so a re-run never overwrites a human's edit
--
-- NO FOREIGN KEYS onto client / user / bubbles — matching V585, V586 and V591.
-- Both tables are tiny (14 rows and 62 rows), so the metadata-lock risk that has hung
-- boot on DDL migrations before does not apply here.
--
-- RESERVED-WORD CHECK
--   `role` is not reserved in MariaDB 10.11 (it is in MySQL 8 as a non-reserved
--   keyword only). `member` is a value, not an identifier. `LINES` — the word that
--   broke V534 — does not appear.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   ALTER TABLE client_account_role MODIFY role ENUM('RESPONSIBLE','SUPPORTED_BY','MEMBER') NOT NULL;
--   DELETE FROM client_account_role;
--   INSERT INTO client_account_role SELECT * FROM bak_car_v598;
--   ALTER TABLE client_account_role MODIFY role ENUM('RESPONSIBLE','SUPPORTED_BY') NOT NULL;
--   (bubble_members is untouched, so the source data survives regardless.)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Snapshot, before anything is deleted
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bak_car_v598 AS SELECT * FROM client_account_role;

CREATE TABLE IF NOT EXISTS bak_bm_v598 AS
SELECT bm.*
  FROM bubble_members bm
  JOIN bubbles b ON b.uuid = bm.bubbleuuid
 WHERE b.type = 'ACCOUNT_TEAM';

-- ----------------------------------------------------------------------------
-- 2. Widen the enum. MEMBER must exist before the inserts below.
-- ----------------------------------------------------------------------------
ALTER TABLE client_account_role
    MODIFY role ENUM ('RESPONSIBLE','SUPPORTED_BY','MEMBER') NOT NULL;

-- ----------------------------------------------------------------------------
-- 3. Resolve every active ACCOUNT_TEAM bubble to at most one client.
--
--    A TEMPORARY table rather than one nested query: the priority rule needs the same
--    three-source union twice (once to pick the best priority per bubble, once to read
--    the client at that priority), and MariaDB 10.11 refuses a correlated subquery
--    inside a derived table — "ERROR 1235 ... SUBQUERY in ROW in left expression of
--    IN/ALL/ANY" — which is how the first draft of this statement failed.
--    The table is connection-scoped and gone when the migration's connection closes.
-- ----------------------------------------------------------------------------
CREATE TEMPORARY TABLE tmp_v598_bubble_client
(
    bubble_uuid CHAR(36) NOT NULL,
    client_uuid CHAR(36) NOT NULL,
    priority    TINYINT  NOT NULL,
    PRIMARY KEY (bubble_uuid, priority)
) ENGINE = MEMORY;

-- 3a. the hand-made link
INSERT IGNORE INTO tmp_v598_bubble_client (bubble_uuid, client_uuid, priority)
SELECT b.uuid, ca.client_uuid, 1
  FROM bubbles b
  JOIN client_account ca ON ca.account_team_bubble_uuid = b.uuid
 WHERE b.type = 'ACCOUNT_TEAM'
   AND b.active = 1;

-- 3b. the bubble name, trimmed of the stray carriage returns four of them carry.
--     CLIENT or PROSPECT — see 3c for why the type filter is not just 'CLIENT'.
INSERT IGNORE INTO tmp_v598_bubble_client (bubble_uuid, client_uuid, priority)
SELECT b.uuid, c.uuid, 2
  FROM bubbles b
  JOIN client c
    ON c.name = TRIM(REPLACE(REPLACE(b.name, '\r', ''), '\n', ''))
   AND c.type IN ('CLIENT', 'PROSPECT')
 WHERE b.type = 'ACCOUNT_TEAM'
   AND b.active = 1;

-- 3c. the five pairs Hans confirmed on 2026-09-14 (spec §5)
INSERT IGNORE INTO tmp_v598_bubble_client (bubble_uuid, client_uuid, priority)
SELECT b.uuid, c.uuid, 3
  FROM bubbles b
  JOIN (SELECT 'Ascendis' AS bubble_name, 'Ascendis Pharma' AS client_name
        UNION ALL
        SELECT 'Erhverstyrelsen', 'Erhvervsstyrelsen'
        UNION ALL
        SELECT 'Udvikling og Forenklingsstyrrelsen', 'Udviklings- og Forenklingsstyrelsen'
        UNION ALL
        SELECT 'Forsvaret', 'Forsvarskommandoen'
        UNION ALL
        SELECT 'Rambøll', 'Ramboll') m
    ON m.bubble_name = TRIM(REPLACE(REPLACE(b.name, '\r', ''), '\n', ''))
  -- CLIENT or PROSPECT: "not a PARTNER" is what this filter means. On a fresh database
  -- nothing is a PROSPECT yet when V598 runs (V600 comes later), so this changes no
  -- outcome there — but it makes the statement independent of migration ORDER. Without
  -- it, a re-run against a database that has already seen V600 silently stops resolving
  -- every bubble whose client has since been typed PROSPECT, which is exactly what
  -- happened locally to the Forsvaret pair.
  JOIN client c ON c.name = m.client_name AND c.type IN ('CLIENT', 'PROSPECT')
 WHERE b.type = 'ACCOUNT_TEAM'
   AND b.active = 1;

-- keep only the best-priority resolution per bubble
DELETE t FROM tmp_v598_bubble_client t
  JOIN (SELECT bubble_uuid, MIN(priority) AS best
          FROM tmp_v598_bubble_client
         GROUP BY bubble_uuid) keep
    ON keep.bubble_uuid = t.bubble_uuid
 WHERE t.priority > keep.best;

-- ----------------------------------------------------------------------------
-- 4. Move the memberships
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO client_account_role (uuid, client_uuid, user_uuid, role, created_at, created_by)
SELECT UUID(), r.client_uuid, bm.useruuid, 'MEMBER', NOW(), 'V598'
  FROM tmp_v598_bubble_client r
  JOIN bubble_members bm ON bm.bubbleuuid = r.bubble_uuid
 WHERE bm.useruuid IS NOT NULL
   -- never list a person twice on one account, whatever role they already hold
   AND NOT EXISTS (SELECT 1
                     FROM client_account_role x
                    WHERE x.client_uuid = r.client_uuid
                      AND x.user_uuid = bm.useruuid)
   -- the owner is not also a member
   AND NOT EXISTS (SELECT 1
                     FROM client c2
                    WHERE c2.uuid = r.client_uuid
                      AND c2.accountmanager = bm.useruuid)
   -- people who have left are not migrated (Hans, 2026-09-14)
   AND NOT EXISTS (SELECT 1
                     FROM userstatus us
                    WHERE us.useruuid = bm.useruuid
                      AND us.status = 'TERMINATED'
                      AND us.statusdate = (SELECT MAX(us2.statusdate)
                                             FROM userstatus us2
                                            WHERE us2.useruuid = bm.useruuid));

DROP TEMPORARY TABLE IF EXISTS tmp_v598_bubble_client;

-- ----------------------------------------------------------------------------
-- 5. Drop the owner mirror, then narrow the enum.
--    Order matters: a RESPONSIBLE row left behind when the enum narrows becomes ''.
-- ----------------------------------------------------------------------------
DELETE FROM client_account_role WHERE role = 'RESPONSIBLE';

ALTER TABLE client_account_role
    MODIFY role ENUM ('SUPPORTED_BY','MEMBER') NOT NULL
        COMMENT 'Supported by / team member. The owner is client.accountmanager (V598)';
