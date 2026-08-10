-- V483: Airtable migration tooling (ATS P21 — Airtable migration & retirement).
--
-- Three tables, all additive:
--
-- 1. recruitment_airtable_practice_mapping — the config table required by
--    spec §10: Airtable faglighed / pipeline values map to practice uuids
--    at runtime, never through hardcoded codes. The practice registry is
--    runtime-mutable, so new practices (e.g. GEN) slot in by adding a row
--    here — the importer is never touched.
--
-- 2. recruitment_airtable_import_runs — one row per dry-run or import
--    invocation, with the reconciliation report persisted as JSON. Status
--    lives in the database, not in memory, because deploys kill in-flight
--    jobs silently (employee-docs migration lesson) — a card reading this
--    table never shows a stale in-memory state.
--
-- 3. recruitment_airtable_records — the per-record import ledger keyed on
--    the Airtable record id. This is the idempotency mechanism: a re-run
--    (after a partial failure or a second final import) skips every record
--    that already has an IMPORTED row, so no candidate is ever duplicated.

CREATE TABLE IF NOT EXISTS recruitment_airtable_practice_mapping (
    uuid           VARCHAR(36)  NOT NULL,
    airtable_value VARCHAR(200) NOT NULL
        COMMENT 'Verbatim Airtable value (faglighed select or pipeline/table name), matched case-insensitively after trim',
    practice_uuid  VARCHAR(36)  NOT NULL
        COMMENT 'FK practice.uuid — the runtime-mutable registry reference (never a code)',

    -- Audit columns (house Auditable pattern, V421)
    created_at  DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    updated_at  DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    created_by  VARCHAR(36) NOT NULL COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL COMMENT 'users.uuid from X-Requested-By',

    PRIMARY KEY (uuid),
    UNIQUE KEY uk_ratpm_value (airtable_value),
    CONSTRAINT fk_ratpm_practice
        FOREIGN KEY (practice_uuid) REFERENCES practice (uuid)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='ATS P21: Airtable value -> practice registry mapping (spec §10)';

CREATE TABLE IF NOT EXISTS recruitment_airtable_import_runs (
    uuid        VARCHAR(36) NOT NULL,
    mode        VARCHAR(10) NOT NULL COMMENT 'DRY_RUN or IMPORT',
    status      VARCHAR(20) NOT NULL COMMENT 'RUNNING, COMPLETED, BLOCKED or FAILED',
    started_at  DATETIME    NOT NULL,
    finished_at DATETIME    NULL,
    started_by  VARCHAR(36) NOT NULL COMMENT 'users.uuid from X-Requested-By',
    report      JSON        NULL COMMENT 'Reconciliation report (counts per table/status, unmapped values, skips, triage list)',
    error       TEXT        NULL COMMENT 'Failure detail when status=FAILED',

    PRIMARY KEY (uuid),
    KEY idx_ratir_started (started_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='ATS P21: Airtable dry-run / import invocations with persisted reconciliation reports';

CREATE TABLE IF NOT EXISTS recruitment_airtable_records (
    airtable_record_id VARCHAR(30)  NOT NULL
        COMMENT 'Airtable rec... id — the cross-run idempotency key',
    airtable_table     VARCHAR(200) NOT NULL COMMENT 'Source table (team pipeline) in the Airtable base',
    run_uuid           VARCHAR(36)  NOT NULL COMMENT 'FK recruitment_airtable_import_runs.uuid of the run that wrote this row',
    candidate_uuid     VARCHAR(36)  NULL COMMENT 'Created recruitment_candidates.uuid (NULL when skipped)',
    application_uuid   VARCHAR(36)  NULL COMMENT 'Created recruitment_applications.uuid (NULL for pool-only records)',
    position_uuid      VARCHAR(36)  NULL COMMENT 'Position the application was attached to (synthetic or mapped)',
    status             VARCHAR(20)  NOT NULL COMMENT 'IMPORTED or SKIPPED',
    skip_reason        VARCHAR(200) NULL COMMENT 'Why the record was skipped (reconciliation contract: skipped-with-reason)',
    imported_at        DATETIME     NOT NULL,

    PRIMARY KEY (airtable_record_id),
    KEY idx_ratr_run (run_uuid),
    KEY idx_ratr_candidate (candidate_uuid),
    CONSTRAINT fk_ratr_run
        FOREIGN KEY (run_uuid) REFERENCES recruitment_airtable_import_runs (uuid)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='ATS P21: per-Airtable-record import ledger (idempotent re-runs, retention-triage join)';
