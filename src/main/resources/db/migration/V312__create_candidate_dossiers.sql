-- ===================================================================
-- V312: Create candidate_dossiers table
-- ===================================================================
-- Feature: Recruitment Dossier
-- Domain:  recruitmentservice
--
-- Purpose:
--   Working draft of the document(s) that will be sent to a candidate
--   for review or signature. A dossier is bound to ONE template per
--   candidate (uniqueness enforced). It accumulates current placeholder
--   values, signer config, and appendices; each "Send" action snapshots
--   them into a CandidateDossierRevision (V313).
--
-- Lifecycle:
--   OPEN   -> dossier is editable; revisions can be allocated.
--   CLOSED -> dossier is read-only. Auto-closed when candidate moves
--             to a terminal state (HIRED / DECLINED / WITHDRAWN).
--
-- Internal FK (recruitment-only): candidate_uuid -> recruitment_candidates(uuid)
-- Soft FK (cross-context):        template_uuid  -> document_templates(uuid)
--
-- JSON columns (current draft state, NOT immutable):
--   placeholder_values_json -- map<placeholderKey, value>
--   signers_config_json     -- ordered array of signer entries
--   appendices_json         -- ordered array of appendix metadata refs
--   These are mutated freely while OPEN. Snapshots live in V313.
--
-- Author: Claude Code
-- Date:   2026-05-04
-- Rollback: DROP TABLE candidate_dossiers;
-- ===================================================================

START TRANSACTION;

CREATE TABLE candidate_dossiers (
    -- Primary key
    uuid VARCHAR(36) PRIMARY KEY
        COMMENT 'Dossier identifier',

    -- Internal FK to candidate (RESTRICT: never silently delete a candidate with dossiers)
    candidate_uuid VARCHAR(36) NOT NULL
        COMMENT 'FK to recruitment_candidates.uuid',

    -- Cross-context soft FK (no DB FK constraint)
    template_uuid VARCHAR(36) NOT NULL
        COMMENT 'Soft-FK to document_templates.uuid (NO DB FK constraint, cross-context reference)',

    -- Working draft state (mutable while OPEN)
    placeholder_values_json JSON NULL
        COMMENT 'Current placeholder values map; mutated until next Send action; snapshotted into revisions',
    signers_config_json     JSON NULL
        COMMENT 'Current signer config array; mutated until next Send action; snapshotted into revisions',
    appendices_json         JSON NULL
        COMMENT 'Current appendix metadata refs; mutated until next Send action; snapshotted into revisions',

    -- State machine
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        COMMENT 'Dossier status enum (Java @Enumerated(EnumType.STRING)): OPEN | CLOSED',

    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP                                  NOT NULL
        COMMENT 'Record creation timestamp',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP      NOT NULL
        COMMENT 'Last modification timestamp (bumps on autosave)',

    -- Internal FK: dossier -> candidate (RESTRICT per spec)
    CONSTRAINT fk_cd_candidate
        FOREIGN KEY (candidate_uuid)
        REFERENCES recruitment_candidates(uuid)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    -- Uniqueness: at most one dossier per (candidate, template)
    CONSTRAINT uk_dossier_candidate_template
        UNIQUE (candidate_uuid, template_uuid),

    -- Status enum guard
    CONSTRAINT chk_cd_status_enum
        CHECK (status IN ('OPEN','CLOSED'))

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Recruitment Dossier: working draft per candidate+template';

-- Indexes per AC #2
CREATE INDEX idx_cd_candidate ON candidate_dossiers(candidate_uuid);
CREATE INDEX idx_cd_status    ON candidate_dossiers(status);

COMMIT;

-- ===================================================================
-- Migration Notes
-- ===================================================================
-- - candidate_uuid FK is ON DELETE RESTRICT: candidates with any dossier
--   cannot be hard-deleted; recruiters must DECLINE/WITHDRAW first
--   (which closes dossiers but does not delete them).
-- - template_uuid is a soft FK: the recruitment context does not depend
--   on the document_templates lifecycle; if a template is archived, the
--   dossier remains valid (revisions hold all snapshot data anyway).
-- - JSON columns are NULL-tolerant for fresh dossiers; the application
--   layer initializes empty maps/arrays. We do NOT use JSON_VALID(...)
--   CHECK constraints because MariaDB 10.x JSON validation is enforced
--   by the storage engine via JSON_VALID at insert/update.
-- - Rollback: DROP TABLE candidate_dossiers; (V313 and V314 reference
--   this table; drop them first when reverting the full feature).
-- ===================================================================
