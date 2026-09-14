-- ============================================================================
-- V604 — a person at the client becomes a row
--
-- Spec: docs/specs/intra-crm-relationships-people-2026-09-14.md §3.1, §5.1
-- Domain: aggregates/crm/person
--
-- WHAT THIS IS
--   Three tables.
--     account_person            one row per person we know at one account
--     account_person_identity   the keys two sightings are merged on
--     account_person_build_state  the singleton the rebuild stamps
--
-- WHY A TABLE AND NOT A DERIVATION
--   Until now a person at a client WAS their display name, re-derived on every
--   request. That works until somebody wants to point at one. A claim ("I know
--   her, we worked together at KMD") and a star ("she matters on this account")
--   both need something to point AT, and a display name is not it: the same
--   human arrives as "Sara Louise Vest (XSVES)" from one mailbox, "Sara Vest"
--   from another and a TrustLink personId from a third, and tomorrow a fourth
--   mailbox spells her a fourth way. A name that moves cannot carry a claim.
--
--   So the registry is DERIVED — no field on it is ever typed by a human — and
--   MATERIALISED, so the identity survives the spelling.
--
-- HOW THE MERGE WORKS, AND WHY account_person_identity EXISTS SEPARATELY
--   Within one client, two sightings are one person when they share ANY identity
--   row. Three kinds:
--     EMAIL      lower-cased address, from account_meeting_attendee.email
--     TRUSTLINK  trustlink_connection.person_id, under an ENABLED alias only
--     NAME       the name key (spec §4.3), written by every source
--   A calendar address and a TrustLink person with the same name key merge. Two
--   addresses with the same name key merge -- one person, two mailboxes. Two
--   TrustLink ids with the same name key merge, which is what the 26 people
--   TrustLink lists under more than one company need.
--
--   The key deliberately ignores middle names and initials. That is the whole
--   point: it is what makes "Sara Louise Vest" and "Sara Vest" one person. The
--   cost is recorded below.
--
-- WHY sources IS A VARCHAR AND NOT A MySQL SET
--   The spec writes SET(CALENDAR,TRUSTLINK,SIGNAL,SLACK). A native SET column
--   maps through Hibernate as a string anyway, and only after an AttributeConverter
--   nobody reading the entity would expect. A comma-joined VARCHAR(60) is the
--   same information with no surprise in the mapping layer, and the set is small
--   enough that 60 characters holds every combination with room over.
--
-- NO FOREIGN KEYS onto client or user, matching V585-V603. client_uuid and
--   alumni_user_uuid are plain identifiers, so a client the CRM deletes cannot
--   block a rebuild and a user row cannot be pinned by a mirror of somebody
--   else's data. The one FK here is parent to child INSIDE the feature
--   (identity -> person, ON DELETE CASCADE), for V593's reason: both rows are
--   written by the same service in the same transaction, an identity is
--   meaningless without its person, and the retention purge must not have to
--   remember to sweep two tables by hand.
--
-- INDEX WIDTH — uq_account_person_identity (client_uuid, kind, value)
--   CHAR(36) utf8mb4 = 144 bytes, a three-value ENUM = 1 byte, VARCHAR(320)
--   utf8mb4 = 1280 bytes plus a 2-byte length prefix = 1282. Total 1427 bytes,
--   comfortably under the 3072-byte index limit of InnoDB's DYNAMIC row format.
--   VARCHAR(320) is the deliberate length: RFC 5321's maximum, and the length
--   account_meeting_attendee.email already uses, so an address never truncates
--   on the way into an identity row.
--
-- THIRD-PARTY PERSONAL DATA — and, for the first time, a purge that covers it
--   account_person and account_person_identity are the TWO NEW STORES OF
--   THIRD-PARTY PERSONAL DATA this cut adds. Between them they hold the name of
--   a person at a client organisation, the client's own shorthand initials for
--   them, their job title, their LinkedIn URL and their e-mail address. Nobody
--   in either table consented to being in it.
--
--   V588, V590, V593, V594, V596 and V602 each added a table like this and each
--   recorded the same gap in its header: spec §3.4/§3.7 promise erasure 24
--   months after the account's last activity, and the purge job was never built.
--   THAT CHANGES IN THIS CUT. V608 builds CrmRetentionPurgeJob, and both tables
--   above are on its sweep: a person row is deleted (identities and claims
--   follow through their cascades) unless a live trustlink_connection still
--   backs it, in which case the TrustLink staleness rule owns it instead.
--
--   V608 ships the job DISARMED — a config kill switch plus an app_settings row
--   seeded 'false' — so read this as "the sweep exists and is wired to these
--   tables", not as "these tables are being erased today". Flipping that row is
--   the moment automatic deletion starts.
--
-- STAGING SYNC
--   All three tables are excluded from sp_sync_prod_to_staging. The exclusion is
--   NOT emitted here: MySQL cannot patch a stored procedure, so every exclusion
--   costs a verbatim re-emission of the whole ~490-line body, and five files in
--   this cut each re-emitting it would be five chances to copy from the wrong
--   definer. There is exactly ONE re-emission in this cut and it is in V608,
--   which adds all five of the cut's new tables in one block. V604, V605, V606
--   and V607 deliberately touch the procedure not at all.
--
-- NEVER-RUN IS TWO STATES, NOT ONE
--   account_person_build_state is seeded with one row (id = 1) whose last_run_at
--   is NULL. A reader asking "has the rebuild ever run?" must treat BOTH "no row
--   at all" and "a row whose last_run_at IS NULL" as never-run. This is the
--   TrustLink trap in V596's sync-state table repeated exactly: a seed that fails
--   or a database restored from before the seed leaves no row, and code that only
--   checks last_run_at throws instead of rebuilding.
--
-- WHAT THIS COSTS, recorded rather than hidden
--   · Two more stores of third-party personal data, as above. The honest total
--     for the cut is three: account_relation_claim in V605 makes it three.
--   · The registry LAGS its sources by up to a day for calendar, TrustLink and
--     Slack -- the same day those syncs already lag. Signals and alias edits
--     rebuild their client immediately.
--   · The name merge can join two humans who share a first and last name at one
--     client. Both sources then land on one row, which is visible and rare. The
--     alternative is one human as two rows on every account, which is neither.
--   · Order-of-magnitude size: TrustLink alone mirrors 1,876 named people today,
--     so this is thousands of rows, not millions.
--
-- RESERVED-WORD CHECK (MariaDB 10.11, written down because of the V534 `lines`
--   incident). Every new column name was checked: uuid, client_uuid, name,
--   name_key, initials, title, kind, alumni_user_uuid, linkedin_url, sources,
--   first_seen_at, last_seen_at, person_uuid, value, id, clients_built,
--   people_upserted, identities_upserted, failures, failure_code, last_run_at,
--   last_success_at. None is reserved. Three are worth naming:
--     `value`   VALUES is reserved, VALUE is not -- it is the keyword in
--               INSERT ... VALUE and is a plain non-reserved identifier.
--     `sources` SOURCE is a mysql CLI client command, not a server keyword, and
--               SOURCES is not a keyword at all (V596 established this).
--     `name`, `kind`, `title` are non-reserved keywords and legal unquoted.
--   `LINES` -- the word that broke V534 -- does not appear.
--
-- COLLATION: utf8mb4_general_ci, as V585-V603. client_uuid and alumni_user_uuid
--   join general_ci tables; a unicode_ci mix does not fail the migration, it
--   fails later, at runtime, with ERROR 1267 in a query nobody changed.
--
-- Idempotency: CREATE TABLE IF NOT EXISTS and INSERT IGNORE.
--
-- Author: Claude Code
-- Date:   2026-09-14
-- Rollback:
--   DROP TABLE account_person_identity, account_person_build_state, account_person;
--   (identity first: it is the child. Nothing else in this file has state.)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. The registry: one row per person at one account.
--
--    Every column is derived. The rebuild never deletes a row -- a claim or a
--    star has to survive a source going quiet, an alias being disabled or a
--    misclassification being corrected. Rows leave only through the V608 purge.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_person
(
    uuid             CHAR(36)     NOT NULL,
    client_uuid      CHAR(36)     NOT NULL,
    name             VARCHAR(255) NOT NULL COMMENT 'The name we show: TrustLink''s full_name if there is one (the person''s own spelling), else the longest calendar name, else the signal''s',
    name_key         VARCHAR(190) NOT NULL COMMENT 'PersonNames.key(): lower(first token)|lower(last token)',
    initials         VARCHAR(16)  NULL COMMENT 'The client''s own shorthand when a mailbox carries one (XSVES, STMJ); NULL when no source spelled one. Shown as a muted chip, because that is how a colleague will hear the person referred to on site',
    title            VARCHAR(500) NULL COMMENT 'Job title: TrustLink position first (the person''s own LinkedIn), else a signal''s person_role, else a Slack reading''s role. NULL = no source carried one. 500 because real titles are long',
    kind             ENUM ('CONTACT','ALUMNI','COLLEAGUE') NOT NULL DEFAULT 'CONTACT' COMMENT 'COLLEAGUE rows are built and then never leave the backend -- they exist so the rebuild can remember that this name is one of ours and keep dropping it',
    alumni_user_uuid CHAR(36)     NULL COMMENT 'For ALUMNI and COLLEAGUE: the user row the name resolved to. NULL for CONTACT, which is every person who matched no employee past or present',
    linkedin_url     VARCHAR(500) NULL COMMENT 'From TrustLink, as TrustLink has it. There is no allow-list on this column and never has been (V596 declares the same column with no CHECK and no trigger); the guard is isSafeExternalUrl in the frontend, on every render',
    sources          VARCHAR(60)  NOT NULL DEFAULT '' COMMENT 'Comma-joined CALENDAR,TRUSTLINK,SIGNAL,SLACK',
    first_seen_at    DATETIME     NOT NULL COMMENT 'Set on INSERT only; never moves',
    last_seen_at     DATETIME     NOT NULL COMMENT 'Stamped by every rebuild a source still backs the row on. A row whose last_seen_at has stopped moving is a person the sources have gone quiet about -- it is NOT deleted for that',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_account_person_client_key (client_uuid, name_key),
    KEY idx_account_person_client_kind (client_uuid, kind),
    KEY idx_account_person_last_seen (last_seen_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Third-party personal data: the people we know at an account, derived from calendar, TrustLink, signals and Slack -- never typed. Purpose: commercial relationship management. Retention 24 months after the account last saw activity, swept by CrmRetentionPurgeJob (V608), which ships DISARMED.';

-- ----------------------------------------------------------------------------
-- 2. The identities two sightings are merged on. One row per (kind, value) per
--    client, which is what the UNIQUE key says: an address or a TrustLink id
--    belongs to at most one person ON ONE ACCOUNT. The same address at two
--    clients is deliberately two rows -- a consultant who changed employer is
--    two different relationships, not one.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_person_identity
(
    uuid          CHAR(36)     NOT NULL,
    person_uuid   CHAR(36)     NOT NULL,
    client_uuid   CHAR(36)     NOT NULL COMMENT 'Denormalised from the person on purpose: the UNIQUE key below is per client, and a merge lookup must not have to join to find out which client it is scoped to',
    kind          ENUM ('EMAIL','TRUSTLINK','NAME') NOT NULL,
    value         VARCHAR(320) NOT NULL COMMENT 'EMAIL: lower-cased address. TRUSTLINK: trustlink_connection.person_id. NAME: the name key. 320 = RFC 5321, matching account_meeting_attendee.email',
    first_seen_at DATETIME     NOT NULL COMMENT 'Set on INSERT only',
    last_seen_at  DATETIME     NOT NULL COMMENT 'Stamped by every rebuild the source still produced this identity on',
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_account_person_identity (client_uuid, kind, value),
    KEY idx_account_person_identity_person (person_uuid),
    CONSTRAINT fk_account_person_identity FOREIGN KEY (person_uuid)
        REFERENCES account_person (uuid) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Third-party personal data: the merge keys for account_person -- e-mail addresses, TrustLink ids and name keys. Deleted with their person by the cascade, which is why the V608 purge sweeps one table and not two.';

-- ----------------------------------------------------------------------------
-- 3. The rebuild's bookkeeping, a singleton exactly as trustlink_sync_state is
--    (V596), so the counters are typed instead of living as strings in
--    app_settings.
--
--    last_run_at is deliberately separate from last_success_at: a rebuild that
--    failed every client still ran, and a recovery job that cannot tell those
--    apart re-runs a rebuild that is failing, hourly, for ever.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_person_build_state
(
    id                  TINYINT     NOT NULL COMMENT 'Always 1; a singleton row so the counters are typed',
    last_run_at         DATETIME    NULL COMMENT 'NULL = never run. So is a missing row -- readers must treat both as never-run',
    last_success_at     DATETIME    NULL COMMENT 'Deliberately separate from last_run_at: a run that failed every client still ran',
    clients_built       INT         NOT NULL DEFAULT 0,
    people_upserted     INT         NOT NULL DEFAULT 0,
    identities_upserted INT         NOT NULL DEFAULT 0,
    failures            INT         NOT NULL DEFAULT 0,
    failure_code        VARCHAR(40) NULL COMMENT 'A CODE only, never an upstream body',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Singleton bookkeeping for AccountPersonService.rebuildAll(). Counts only -- no name, no client, no address ever lands here.';

-- The seed. INSERT IGNORE rather than a plain INSERT so a re-run never resets a
-- live counter, and rather than ON DUPLICATE KEY UPDATE because there is nothing
-- to update: the row's whole purpose is to exist with NULLs in it.
--
-- Do not read this seed as a guarantee. A database restored from before V604, or
-- a seed that lost a race, leaves no row at all, and hasNeverRun() must answer
-- true for that as well as for last_run_at IS NULL.
INSERT IGNORE INTO account_person_build_state
    (id, last_run_at, last_success_at, clients_built, people_upserted, identities_upserted, failures, failure_code)
VALUES
    (1, NULL, NULL, 0, 0, 0, 0, NULL);
