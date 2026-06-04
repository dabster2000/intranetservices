-- =============================================================================
-- Migration V362: Create phantom_client_map
--
-- Purpose:
--   Maps a recurring phantom clientname (an e-conomic account label such as
--   'Konsulenthonorar Vattenfall') to a real client, or marks it excluded
--   (e.g. canteen). One row per distinct label. Drives nightly phantom
--   attribution (PhantomAttributionService).
--
-- New table:
--   phantom_client_map (clientname PK, client_uuid, excluded, note,
--                       confirmed_by, confirmed_at, created_at, updated_at)
--
-- Backwards compatibility:
--   Additive only (new table). No existing table/column altered or dropped.
--   Inert until PhantomAttributionService (later phase) reads it.
--
-- Soft FKs:
--   client_uuid -> client.uuid and confirmed_by -> user.uuid are soft FKs
--   (project convention: no cross-service FK constraints).
--
-- Affected Java:
--   dk.trustworks.intranet.aggregates.invoice.model.PhantomClientMap
--   dk.trustworks.intranet.aggregates.invoice.services.PhantomAttributionService (later phase)
--
-- Idempotency:
--   CREATE TABLE is not re-runnable; Flyway versioning guarantees single apply.
--
-- Rollback strategy (manual — Flyway does not auto-rollback DDL):
--   DROP TABLE phantom_client_map;
-- =============================================================================

CREATE TABLE phantom_client_map (
    clientname    VARCHAR(255) NOT NULL,            -- the phantom label (PK)
    client_uuid   VARCHAR(36)  DEFAULT NULL,        -- resolved client (soft FK -> client.uuid); NULL when excluded/unset
    excluded      TINYINT(1)   NOT NULL DEFAULT 0,  -- 1 = known non-client label, never attribute (e.g. kantine)
    note          VARCHAR(500) DEFAULT NULL,
    confirmed_by  VARCHAR(36)  DEFAULT NULL,        -- user uuid who confirmed (soft FK -> user.uuid)
    confirmed_at  DATETIME     DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (clientname),
    INDEX idx_pcm_client (client_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- Validation queries (run manually after deploy; not executed by Flyway):
--
-- 1. Table exists with the right columns:
--    SELECT column_name, data_type, is_nullable, column_default
--    FROM information_schema.columns
--    WHERE table_name = 'phantom_client_map' ORDER BY ordinal_position;
--
-- 2. Empty on first deploy:
--    SELECT COUNT(*) AS should_be_zero FROM phantom_client_map;
--
-- 3. PK + index present:
--    SHOW INDEX FROM phantom_client_map;
-- -----------------------------------------------------------------------------
