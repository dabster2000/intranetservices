-- ============================================================================
-- V599 — the account-team bubble stops being the store
--
-- Spec: docs/specs/intra-crm-account-people-merge-2026-09-14.md §2 (V599), §9 Q4, Q5
--
-- Hans decided on 2026-09-14 to ship this in the SAME release as V598 rather than
-- holding it one release, and to deactivate the bubbles once their members have moved.
--
-- WHAT THIS DOES
--   1. Drops client_account.account_team_bubble_uuid. V598 has already read it; the
--      column, the DTO and the patch request all go in one step.
--   2. Deactivates the 14 ACCOUNT_TEAM bubbles so they stop appearing on
--      /knowledge/bubbles and in the (now removed) account-team picker.
--
-- WHAT THIS DOES NOT DO
--   · BubbleType.ACCOUNT_TEAM stays a valid enum value. 16 rows still carry it and
--     removing the value would break their deserialisation.
--   · bubble_members is not deleted. It is the source V598 read from and the only copy
--     of the 4 terminated memberships V598 deliberately did not migrate.
--   · client_account.slack_space is untouched. The account's Slack link was never the
--     bubble's: Rigspolitiet's slack_space is 'a_rigspolitiet' while its bubble's
--     slackchannel is 'GFF4TAY85', a value six different bubbles share.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   ALTER TABLE client_account ADD COLUMN account_team_bubble_uuid CHAR(36) NULL AFTER gtm_bubble_uuid;
--   UPDATE client_account ca JOIN bak_catb_v599 b ON b.client_uuid = ca.client_uuid
--      SET ca.account_team_bubble_uuid = b.account_team_bubble_uuid;
--   UPDATE bubbles SET active = 1 WHERE type = 'ACCOUNT_TEAM';
-- ============================================================================

-- The four hand-made links, kept before the column goes. Dropping a column is the one
-- change in this pair that throws away a value nothing else holds a copy of.
CREATE TABLE IF NOT EXISTS bak_catb_v599 AS
SELECT client_uuid, account_team_bubble_uuid
  FROM client_account
 WHERE account_team_bubble_uuid IS NOT NULL;

ALTER TABLE client_account
    DROP COLUMN IF EXISTS account_team_bubble_uuid;

UPDATE bubbles
   SET active = 0
 WHERE type = 'ACCOUNT_TEAM'
   AND active = 1;
