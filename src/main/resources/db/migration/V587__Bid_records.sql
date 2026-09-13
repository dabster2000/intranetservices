-- ============================================================================
-- V587: Bid records — tilbud, udbud and SKI mini-tenders per account
-- ============================================================================
-- Feature: CRM spec §3.6 / §4.3 tab 6, built out by
--          docs/specs/account-page-completion-2026-09-13.md.
-- Domain:  aggregates/crm/bid
--
-- WHAT THIS IS
--   The account page's Bids tab shipped on 2026-09-12 rendering invented rows: random
--   titles, random competitors, a win rate computed over fiction. No bid record has
--   ever existed anywhere in Intra. This is the table the spec asked for.
--
--   A bid is not a lead. A lead is a piece of work we might be asked to staff; a bid is
--   a document we chose to write, or chose not to. The go/no-go decision, the price we
--   put in, who we lost to and the post-mortem have no home on sales_lead and would
--   distort the pipeline if they were forced onto it. lead_uuid links the two when a
--   bid did produce a lead.
--
-- OUTCOME RULES (enforced in BidService, not by a CHECK — see V586's note on why this
-- schema does not trust DB CHECK constraints to exist everywhere):
--   * go_nogo = 'NOGO'  forces outcome = 'NOGO'; a bid we declined has no result.
--   * competitor is only accepted when outcome = 'LOST'; naming a competitor on a bid
--     we won is a claim we cannot support.
--   * post_mortem is free text and is NEVER shown in an aggregate (spec §3.6).
--
-- WIN RATE is not stored. It is won / (won + lost) over bids with a decided outcome,
--   computed at read time — the account page already does exactly this arithmetic.
--
-- NO FOREIGN KEYS onto client / sales_lead / user, matching V219, V584 and V585.
--
-- RESERVED-WORD CHECK: none of title, type, price, outcome, competitor or owner_uuid is
--   a MariaDB reserved word. `bid` as a table name is safe.
--
-- COLLATION: utf8mb4_general_ci, as V585/V586 — client_uuid joins `client`.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS and INSERT ... ON DUPLICATE KEY UPDATE.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   DROP TABLE bid;
--   UPDATE role_permission SET revoked_at = NOW(), modified_by = 'V587-rollback'
--    WHERE permission_key IN ('bids:read','bids:write');
-- ============================================================================

CREATE TABLE IF NOT EXISTS bid
(
    uuid        CHAR(36)       NOT NULL,
    client_uuid CHAR(36)       NOT NULL,
    lead_uuid   CHAR(36)       NULL COMMENT 'sales_lead.uuid when the bid produced one',
    title       VARCHAR(300)   NOT NULL,
    type        ENUM ('TILBUD','UDBUD','SKI_MINI') NOT NULL,
    go_nogo     ENUM ('GO','NOGO','PENDING') NOT NULL DEFAULT 'PENDING',
    price       DECIMAL(14, 2) NULL,
    due_date    DATE           NOT NULL,
    outcome     ENUM ('OPEN','WON','LOST','NOGO','NOT_PUBLISHED') NOT NULL DEFAULT 'OPEN',
    competitor  VARCHAR(200)   NULL COMMENT 'Only set when outcome = LOST',
    post_mortem TEXT           NULL COMMENT 'Free text; never shown in an aggregate',
    owner_uuid  CHAR(36)       NOT NULL,
    created_at  DATETIME       NOT NULL,
    created_by  CHAR(36)       NOT NULL,
    modified_at DATETIME       NOT NULL,
    modified_by CHAR(36)       NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_bid_client (client_uuid, due_date),
    KEY idx_bid_outcome (outcome),
    KEY idx_bid_lead (lead_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Bid records per account: go/no-go, price, outcome, competitor (CRM spec 3.6)';

-- ----------------------------------------------------------------------------
-- Permission rows (metadata only on conflict — see V585 §6)
-- ----------------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('bids:read', 'Bids — read', NULL, 'CRM', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('bids:write', 'Bids — write', NULL, 'CRM', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- bids:read to USER: the bid history sits on an account page every employee can open,
-- and a consultant walking into a client should be able to see we bid there and lost.
-- bids:read is therefore listed in the frontend scanner's UNIVERSAL_PERMISSIONS.
-- bids:write is the SALES tier — writing a bid record is a commercial act.
-- revoked_at is deliberately NOT reset on conflict (see V585 §7).
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES ('USER', 'bids:read', 'ALL', NOW(), 'V587'),
       ('SALES', 'bids:write', 'ALL', NOW(), 'V587'),
       ('PARTNER', 'bids:write', 'ALL', NOW(), 'V587'),
       ('ADMIN', 'bids:write', 'ALL', NOW(), 'V587')
ON DUPLICATE KEY UPDATE data_scope  = VALUES(data_scope),
                        updated_at  = NOW(),
                        modified_by = 'V587';
