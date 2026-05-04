-- ===================================================================
-- V313: Create candidate_dossier_revisions table
-- ===================================================================
-- Feature: Recruitment Dossier
-- Domain:  recruitmentservice
--
-- Purpose:
--   Immutable snapshot of a dossier's state at the moment of a Send
--   action. Revisions are the audit trail and are NEVER mutated after
--   creation. Each Send action allocates a new revision with a
--   gap-less monotonic version_number scoped to the dossier.
--
-- Revision kinds:
--   REVIEW_EMAIL -> manager queued a TrustworksMail to candidate.email
--                   with PDF attachments generated from the snapshot.
--   REVIEW_PDF   -> manager downloaded a generated review PDF (no email).
--   SIGNATURE    -> manager triggered a NextSign multi-document case;
--                   signing_case_key links to signing_cases.case_key.
--
-- Snapshot semantics:
--   - placeholder_values_snapshot, signers_config_snapshot,
--     appendices_snapshot are NOT NULL JSON. They MUST contain the full
--     state at allocation time; subsequent dossier edits do NOT mutate
--     prior revisions (verified by service test in backend AC #6).
--
-- Cross-context soft FK:
--   signing_case_key -> signing_cases.case_key (NO DB FK constraint).
--   This column is NULL for REVIEW_EMAIL / REVIEW_PDF revisions.
--
-- Author: Claude Code
-- Date:   2026-05-04
-- Rollback: DROP TABLE candidate_dossier_revisions;
-- ===================================================================

START TRANSACTION;

CREATE TABLE candidate_dossier_revisions (
    -- Primary key
    uuid VARCHAR(36) PRIMARY KEY
        COMMENT 'Revision identifier',

    -- Internal FK to dossier
    dossier_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK to candidate_dossiers.uuid',

    -- Gap-less monotonic per-dossier version
    version_number INT NOT NULL
        COMMENT '1-based monotonic version per dossier, allocated as max(existing)+1 in entity',

    -- Revision kind
    kind VARCHAR(20) NOT NULL
        COMMENT 'Revision kind enum (Java @Enumerated(EnumType.STRING)): REVIEW_EMAIL | REVIEW_PDF | SIGNATURE',

    -- Immutable snapshots (full payload at moment of Send)
    placeholder_values_snapshot JSON NOT NULL
        COMMENT 'Frozen placeholder values map at allocation time',
    signers_config_snapshot     JSON NOT NULL
        COMMENT 'Frozen signer config array at allocation time',
    appendices_snapshot         JSON NOT NULL
        COMMENT 'Frozen appendix metadata refs at allocation time',

    -- Optional NextSign linkage (SIGNATURE only)
    signing_case_key VARCHAR(255) NULL
        COMMENT 'Soft-FK to signing_cases.case_key (NO DB FK constraint); NULL unless kind=SIGNATURE',

    -- Recipient info captured at send time (audit; survives candidate edits)
    recipient_email VARCHAR(255) NOT NULL
        COMMENT 'Email of recipient at send time (typically candidate.email)',
    sent_by_useruuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK to users.uuid (NO DB FK constraint); the manager who triggered the send',
    note             TEXT NULL
        COMMENT 'Optional note attached to the send action',

    -- Audit
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
        COMMENT 'Revision creation timestamp (immutable; no updated_at)',

    -- Internal FK: revision -> dossier (RESTRICT per spec)
    CONSTRAINT fk_cdr_dossier
        FOREIGN KEY (dossier_uuid)
        REFERENCES candidate_dossiers(uuid)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    -- Uniqueness: gap-less monotonic version per dossier
    CONSTRAINT uk_revision_dossier_version
        UNIQUE (dossier_uuid, version_number),

    -- Kind enum guard
    CONSTRAINT chk_cdr_kind_enum
        CHECK (kind IN ('REVIEW_EMAIL','REVIEW_PDF','SIGNATURE')),

    -- version_number must be positive
    CONSTRAINT chk_cdr_positive_version
        CHECK (version_number > 0)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Recruitment Dossier: immutable revision snapshots per Send action';

-- Indexes per AC #3
-- Primary read pattern: revisions for a dossier, newest first.
CREATE INDEX idx_cdr_dossier_version_desc ON candidate_dossier_revisions(dossier_uuid, version_number DESC);
-- Lookup by NextSign case key (signature completion listener join).
CREATE INDEX idx_cdr_signing_case_key     ON candidate_dossier_revisions(signing_case_key);
-- Filter timeline by revision kind.
CREATE INDEX idx_cdr_kind                 ON candidate_dossier_revisions(kind);

COMMIT;

-- ===================================================================
-- Migration Notes
-- ===================================================================
-- - Revisions are write-once. The application layer enforces this via
--   the entity (no setters); the schema does not need a trigger.
-- - version_number uniqueness within a dossier is the gap-less
--   monotonic guarantee: the service allocates max(existing)+1 inside
--   the same @Transactional, so concurrent inserts will conflict on
--   uk_revision_dossier_version and roll back (caller retries).
-- - signing_case_key is a soft FK: signing_cases owns the case lifecycle
--   independently; dossier revisions only link by case_key string.
-- - placeholder_values_snapshot may contain CPR / salary / pension
--   placeholders. The RecruitmentRevisionResponseFilter (recruitment
--   ContainerResponseFilter) strips known sensitive keys for callers
--   without users:read scope (spec §8.2).
-- - Rollback: DROP TABLE candidate_dossier_revisions;
-- ===================================================================
