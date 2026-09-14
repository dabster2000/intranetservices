-- V615 — merging a duplicate client into the right one.
--
-- WHY. Intra can create the same company twice (the full form only WARNS on a name match
-- and creates anyway), and once it has, nothing could put it back together: on 2026-09-14
-- seven pairs were collapsed by hand in production with a 287-statement SQL file, a
-- restore file and a replay into a scratch schema to prove it. This migration gives that
-- act a home in the application — docs/specs/crm-client-merge-2026-09-14.md.
--
-- THE LOSING ROW IS TOMBSTONED, NOT DELETED (spec D1). `client.merged_into_uuid` points at
-- the survivor; every client listing filters `merged_into_uuid IS NULL`; a stale link or a
-- cached uuid still resolves, and answers with where the company went. With 42 tables and
-- thousands of invoices repointed, "that merge was wrong" has to remain sayable.
--
-- No FK on merged_into_uuid, matching V585/V586/V595/V610 — the legacy `client` table
-- carries plain uuid columns everywhere.
--
-- The audit row records what a merge moved (per table.column), what it dropped as a
-- duplicate on the loser, and — the one thing the merge deliberately does NOT touch —
-- which e-conomic customer numbers are now orphaned in which agreement, so a person can
-- deactivate them by hand in e-conomic (spec §5). The JSON columns are written by
-- ClientMergeService and read by nobody but a person; there is no query on them.

SET SESSION lock_wait_timeout = 20;

ALTER TABLE client
    ADD COLUMN IF NOT EXISTS merged_into_uuid VARCHAR(36) NULL,
    ADD COLUMN IF NOT EXISTS merged_at DATETIME(6) NULL;

-- Filtered on by every client listing; a plain index keeps `IS NULL` cheap on a 300-row
-- table today and on a 3,000-row table later.
ALTER TABLE client
    ADD INDEX IF NOT EXISTS idx_client_merged_into (merged_into_uuid);

CREATE TABLE IF NOT EXISTS client_merge_audit (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    winner_uuid CHAR(36) NOT NULL,
    loser_uuid CHAR(36) NOT NULL,
    loser_name VARCHAR(255) NOT NULL,
    loser_type VARCHAR(10) NOT NULL,
    loser_cvr VARCHAR(20) NULL,
    -- WINNER or LOSER: whose client_account (band, GTM bubble, Slack space, next step)
    -- and whose account manager the survivor keeps when both rows had one (spec D2).
    account_from VARCHAR(10) NOT NULL,
    -- {"work.clientuuid": 145, "invoices.billing_client_uuid": 101, ...}
    moved_json LONGTEXT NOT NULL,
    -- {"account_person": 11, "trustlink_connection": 11, ...} — loser rows removed
    -- because the survivor already held the same unique key.
    dropped_json LONGTEXT NOT NULL,
    -- [{"companyUuid": ..., "companyName": "Trustworks A/S", "customerNumber": 136}]
    -- — the loser's e-conomic customers the survivor already had one for. Live in
    -- e-conomic, no longer mapped by Intra: deactivate by hand.
    orphaned_economics_json LONGTEXT NOT NULL,
    actor_uuid CHAR(36) NOT NULL,
    merged_at DATETIME(6) NOT NULL,
    INDEX idx_client_merge_audit_winner (winner_uuid),
    INDEX idx_client_merge_audit_loser (loser_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------------------------------------------------------------------------
-- The scope (spec D5, §7).
--
-- crm:write is not enough: a merge repoints invoices and contracts, and its e-conomic
-- consequence is a bookkeeping act. `clients:merge` is its own key, granted to the same
-- group that can book invoices — read out of the live invoices:write grants rather than
-- spelled out, so the two cannot drift apart at seed time. Narrowing it later is one
-- revocation row, no deploy.
--
-- The permission row is inserted here as well as by the regenerated V464 seed: V464 has
-- already run everywhere, and repair-at-start realigns its checksum WITHOUT re-running
-- it (see V514 for the same reasoning). role_permission.permission_key is an FK onto
-- permission.permission_key, so the row must exist before the grants.
--
-- `permission` and `role_permission` are excluded from the nightly prod -> staging sync
-- (V500), so these rows survive a refresh on staging.
-- ---------------------------------------------------------------------------
INSERT INTO permission (permission_key, display_name, description, category, origin, state)
VALUES ('clients:merge', 'Clients — merge duplicates', NULL, 'CRM', 'CODE', 'ACTIVE')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), category = VALUES(category);

INSERT INTO role_permission (role, permission_key, data_scope, created_at, created_by)
SELECT rp.role, 'clients:merge', 'ALL', NOW(), 'V615'
FROM role_permission rp
WHERE rp.permission_key = 'invoices:write'
  AND rp.revoked_at IS NULL
ON DUPLICATE KEY UPDATE
    data_scope  = VALUES(data_scope),
    revoked_at  = NULL,
    updated_at  = NOW(),
    modified_by = 'V615';
