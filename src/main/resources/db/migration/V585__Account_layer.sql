-- ============================================================================
-- V585: The account layer — band, roles, band history and client e-mail domains
-- ============================================================================
-- Feature: CRM spec §3.1 / §3.2 (docs/specs/intra-crm-spec-2026-09-12.md), built out
--          by docs/specs/account-page-completion-2026-09-13.md.
-- Domain:  aggregates/crm/account + platform authorization (two permission grants)
--
-- WHAT THIS IS
--   /clients/{uuid} was shipped on 2026-09-12 as a layout whose band, supported-by
--   roles, GTM team, Slack space and next step were INVENTED client-side from a hash
--   of the client uuid. This migration gives every one of those a column, so the
--   page stops guessing.
--
-- NO BACKFILL, ON PURPOSE
--   client_account is NOT pre-populated. A client with no row reads as BACKLOG, no
--   roles, no next step — which is exactly the spec's default ("default BACKLOG for
--   every existing client") and is the honest answer for the ~600 clients nobody has
--   ever triaged. The row is created lazily on the first write. Pre-inserting 600
--   rows would only make "Backlog" look like a decision somebody took.
--
-- RESPONSIBLE MIRRORS client.accountmanager
--   Spec §9.7 left open whether owner_uuid replaces the free-text accountmanager
--   column. It does not, yet. client.accountmanager stays the source of truth for the
--   owner and a RESPONSIBLE row is written alongside it by AccountService, so the two
--   never disagree. Nothing is dropped and nothing has to be migrated in a hurry.
--
-- WHY client_domain HAS A GLOBAL UNIQUE ON domain
--   The calendar join (V588) attributes a meeting to a client by the attendees' e-mail
--   domain. If two clients could claim the same domain the same meeting would land on
--   two accounts and every meeting count would be wrong in a way nobody would notice.
--   One domain, one client — enforced by the database, not by the service.
--
-- SEEDING client_domain
--   Seeded from client.billing_email and client.default_billing_email, skipping a
--   deny-list of freemail and our own domain, and skipping anything already claimed.
--   source='SEEDED' so a human can tell what Intra guessed from what they typed.
--
-- NO FOREIGN KEYS
--   client_uuid / user_uuid / gtm_bubble_uuid are soft references, matching every
--   other table in this schema (V219 client_activity_log, V584 account_signal): the
--   domain is loosely coupled on purpose and a hard constraint would make a client
--   merge or a user cleanup fail on account rows.
--
-- RESERVED-WORD CHECK
--   No column here is a MariaDB reserved word. `band` is not reserved (`LINES` was,
--   which is how V534 took down the staging canary on 2026-08-26). `current` and
--   `rank` are avoided elsewhere in this series for the same reason.
--
-- COLLATION
--   utf8mb4_general_ci: client_uuid joins the legacy `client` table, and unicode_ci
--   against a general_ci table fails at RUNTIME with ERROR 1267 "Illegal mix of
--   collations" (the V315 incident).
--
-- WHY data_scope = 'ALL'
--   DbAuthzStore.loadEffectivePermissions filters on data_scope = 'ALL'; any other
--   scope is invisible to the BFF's requirePermission() and the UI's can(), so the
--   account page would render no write controls at all. Same reasoning as V525/V584.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS and INSERT ... ON DUPLICATE KEY UPDATE
--   throughout; safe to re-run by hand in any environment.
--
-- Author: Claude Code
-- Date:   2026-09-13
-- Rollback:
--   DROP TABLE client_domain, client_band_history, client_account_role, client_account;
--   UPDATE role_permission SET revoked_at = NOW(), modified_by = 'V585-rollback'
--    WHERE permission_key IN ('accounts:read','accounts:write');
--   The permission rows are left in place — revocation is a tombstone in this schema.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The account row: one per client, created on first write
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_account
(
    client_uuid     CHAR(36)     NOT NULL,
    band            ENUM ('STRATEGIC','ACTIVE','BACKLOG') NOT NULL DEFAULT 'BACKLOG',
    gtm_bubble_uuid CHAR(36)     NULL COMMENT 'The Account Team bubble (bubbles.uuid)',
    slack_space     VARCHAR(80)  NULL COMMENT 'Channel name without the leading #, e.g. a_oersted',
    next_step       VARCHAR(200) NULL COMMENT 'Strategic/Active only; one line',
    created_at      DATETIME     NOT NULL,
    created_by      CHAR(36)     NOT NULL,
    modified_at     DATETIME     NOT NULL,
    modified_by     CHAR(36)     NOT NULL,
    PRIMARY KEY (client_uuid),
    KEY idx_client_account_band (band)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Account layer over client: band, GTM team, Slack space and next step (CRM spec 3.1)';

-- ----------------------------------------------------------------------------
-- 2. Who runs the account
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_account_role
(
    uuid        CHAR(36) NOT NULL,
    client_uuid CHAR(36) NOT NULL,
    user_uuid   CHAR(36) NOT NULL,
    role        ENUM ('RESPONSIBLE','SUPPORTED_BY') NOT NULL,
    created_at  DATETIME NOT NULL,
    created_by  CHAR(36) NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_client_account_role (client_uuid, user_uuid, role),
    KEY idx_client_account_role_client (client_uuid),
    KEY idx_client_account_role_user (user_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Responsible / Supported by on an account (CRM spec 3.1)';

-- ----------------------------------------------------------------------------
-- 3. Band history — the BAND rows in the account timeline
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_band_history
(
    uuid        CHAR(36)     NOT NULL,
    client_uuid CHAR(36)     NOT NULL,
    from_band   ENUM ('STRATEGIC','ACTIVE','BACKLOG') NULL COMMENT 'NULL on the first change',
    to_band     ENUM ('STRATEGIC','ACTIVE','BACKLOG') NOT NULL,
    note        VARCHAR(255) NULL,
    changed_by  CHAR(36)     NOT NULL,
    changed_at  DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    KEY idx_client_band_history_client (client_uuid, changed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Every band change, for the account timeline (CRM spec 3.1)';

-- ----------------------------------------------------------------------------
-- 4. Client e-mail domains — how a calendar meeting finds its account
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS client_domain
(
    uuid        CHAR(36)     NOT NULL,
    client_uuid CHAR(36)     NOT NULL,
    domain      VARCHAR(190) NOT NULL COMMENT 'Lower-cased, no @ and no scheme',
    source      ENUM ('SEEDED','MANUAL') NOT NULL DEFAULT 'MANUAL',
    created_at  DATETIME     NOT NULL,
    created_by  CHAR(36)     NOT NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_client_domain_domain (domain),
    KEY idx_client_domain_client (client_uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='One domain belongs to at most one client; used to attribute meetings (CRM spec 3.2)';

-- ----------------------------------------------------------------------------
-- 5. Seed the domains we can already infer from billing e-mail addresses
-- ----------------------------------------------------------------------------
-- INSERT IGNORE rather than ON DUPLICATE KEY UPDATE: a re-run must never move a
-- domain from the client a person assigned it to, back to the one billing happens to
-- point at. First writer wins, and a human always outranks the seed.
INSERT IGNORE INTO client_domain (uuid, client_uuid, domain, source, created_at, created_by)
SELECT UUID(),
       c.uuid,
       d.domain,
       'SEEDED',
       NOW(),
       'V585'
FROM (SELECT uuid,
             LOWER(TRIM(SUBSTRING_INDEX(billing_email, '@', -1))) AS domain
      FROM client
      WHERE billing_email IS NOT NULL
        AND billing_email LIKE '%@%.%'
      UNION
      SELECT uuid,
             LOWER(TRIM(SUBSTRING_INDEX(default_billing_email, '@', -1)))
      FROM client
      WHERE default_billing_email IS NOT NULL
        AND default_billing_email LIKE '%@%.%') AS d
         JOIN client c ON c.uuid = d.uuid
WHERE d.domain <> ''
  AND d.domain NOT LIKE '% %'
  AND d.domain NOT IN (
    -- Freemail and our own domain: a meeting with someone on gmail.com tells us
    -- nothing about which account it belongs to.
    'gmail.com', 'googlemail.com', 'hotmail.com', 'hotmail.dk', 'outlook.com',
    'outlook.dk', 'live.dk', 'live.com', 'yahoo.com', 'yahoo.dk', 'icloud.com',
    'me.com', 'mac.com', 'msn.com', 'protonmail.com', 'proton.me', 'mail.dk',
    'trustworks.dk'
    );

-- ----------------------------------------------------------------------------
-- 6. Permission rows (FK target of role_permission.permission_key)
--
--    Metadata only on conflict, matching the generated seed's contract: never touch
--    state, revoked_at, origin or enforce_acting_user, so a key marked STALE or
--    revoked stays that way across a re-deploy. V464 is regenerated alongside this
--    migration so the seed-drift gate passes, but V464 has already run everywhere and
--    repair-at-start realigns its checksum WITHOUT re-running it — which is why the
--    rows must be inserted HERE, and before the grants below (role_permission
--    .permission_key is an FK onto permission.permission_key, V462).
-- ----------------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('accounts:read', 'Accounts — read', NULL, 'CRM', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('accounts:write', 'Accounts — write', NULL, 'CRM', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

-- ----------------------------------------------------------------------------
-- 7. Grants
-- ----------------------------------------------------------------------------
-- accounts:read goes to USER — every employee can already open a client page, and the
-- header says so in as many words ("Everyone in the firm can read this page"). Nothing
-- salary- or bonus-derived is served by these endpoints; the break-even figure on the
-- rate KPI is filtered server-side by cost role and never crosses the wire otherwise.
-- Because every employee holds it, accounts:read is listed in the frontend scanner's
-- UNIVERSAL_PERMISSIONS — a universally-held key must never pass as a per-user gate.
--
-- accounts:write is the SALES tier, matching crm:write (V468): band, roles and the
-- account plan are commercial decisions, not something any employee makes.
--
-- revoked_at is deliberately NOT reset on conflict. Clearing it would make a re-run of
-- this migration silently undo the documented rollback above, which revokes by
-- tombstone. An intentional re-grant is an explicit UPDATE, never a migration re-run.
INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
VALUES ('USER', 'accounts:read', 'ALL', NOW(), 'V585'),
       ('SALES', 'accounts:write', 'ALL', NOW(), 'V585'),
       ('PARTNER', 'accounts:write', 'ALL', NOW(), 'V585'),
       ('ADMIN', 'accounts:write', 'ALL', NOW(), 'V585')
ON DUPLICATE KEY UPDATE data_scope  = VALUES(data_scope),
                        updated_at  = NOW(),
                        modified_by = 'V585';
