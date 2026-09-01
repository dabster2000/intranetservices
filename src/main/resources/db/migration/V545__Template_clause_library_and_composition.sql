-- ===================================================================
-- V545: Clause library & composition (Phase 2 of the template-clauses
--       & agreement-registry plan)
-- ===================================================================
-- Feature: Template Clauses & Agreement Registry, Phase 2 — negotiated
--          terms become reusable, versioned Word-fragment clauses; one
--          NextSign case carries contract + generated tillæg
--          (spec docs/design/template-clauses-and-agreement-registry-spec.md §4.1–§4.5,
--           plan docs/superpowers/plans/2026-08-31-template-clauses-implementation-plan.md)
-- Domain:  documentservice (e-signature templates) + recruitmentservice
--          (dossier draft/snapshot columns)
--
-- Purpose:
--   1. template_clauses          — one row per reusable clause (key, name,
--                                  agreement type, render mode, category,
--                                  status, pointer to the active version).
--   2. template_clause_versions  — append-only wording history; a version
--                                  becomes immutable once any document was
--                                  sent with it (enforced in code).
--   3. template_clause_placeholders — typed parameters on the CLAUSE
--                                  (stable identity, not the version);
--                                  mirrors template_placeholders and adds
--                                  source_field (prefill) + registry_field
--                                  (Phase 3 registry column mapping).
--   4. template_clause_links     — which clauses a template offers
--                                  (preselected / required / order).
--   5. signing_case_clauses      — immutable snapshot of what a sent case
--                                  contained; the single source for the
--                                  Phase 3 registry writes.
--   6. clause_addendum_shells    — the single shared "Tillæg til
--                                  ansættelsesaftale" wrapper document.
--   7. candidate_dossiers.clauses_json + candidate_dossier_revisions
--      .clauses_snapshot          — dossier draft + frozen Send snapshot.
--   8. Seed the documents.clauses.enabled flag (default OFF — wizard/
--      dossier step and admin tab ship dark).
--
-- NOTE: template_clauses.agreement_type is a plain column here — the
--   agreement_types vocabulary table ships in Phase 3; the FK is added
--   there if wanted. Values are validated in code against the seeded
--   key format.
--
-- Reserved-word check (MariaDB 10.x): uuid, clause_key, name,
--   description, agreement_type, render_mode, category, status,
--   offer_on_category, active_version_uuid, version_number, file_uuid,
--   original_filename, change_note, published_at, published_by,
--   placeholder_key, label, field_type, required, display_order,
--   default_value, help_text, source, source_field, registry_field,
--   field_group, validation_rules, select_options, template_uuid,
--   clause_uuid, preselected, signing_case_id, clause_version_uuid,
--   parameter_values_json, custom_title, custom_text, clauses_json,
--   clauses_snapshot, active, created_at/updated_at/created_by/
--   modified_by — none reserved (the V534 LINES lesson).
--
-- Idempotency: repair-at-start re-runs migrations across checkouts —
--   CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, INSERT IGNORE
--   for the settings seed.

-- 1. Clause library ----------------------------------------------------------

CREATE TABLE IF NOT EXISTS template_clauses (
    uuid                VARCHAR(36)  NOT NULL,
    clause_key          VARCHAR(100) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    description         TEXT         NULL,
    agreement_type      VARCHAR(50)  NULL COMMENT 'Registry vocabulary key (Phase 3), e.g. GARANTIBONUS',
    render_mode         VARCHAR(20)  NOT NULL DEFAULT 'ADDENDUM' COMMENT 'INLINE (merged at the {{CLAUSES}} anchor) or ADDENDUM (combined tillaeg)',
    category            VARCHAR(50)  NOT NULL COMMENT 'Same enum as document_templates.category; clause offered on templates of this category',
    offer_on_category   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Offer on every template of the category, without an explicit link',
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ACTIVE / RETIRED; only ACTIVE is offered to preparers',
    active_version_uuid VARCHAR(36)  NULL COMMENT 'template_clause_versions.uuid used for new documents',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by          VARCHAR(36)  NULL,
    modified_by         VARCHAR(36)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_tc_clause_key (clause_key),
    KEY idx_tc_category_status (category, status)
);

-- 2. Append-only wording history --------------------------------------------

CREATE TABLE IF NOT EXISTS template_clause_versions (
    uuid              VARCHAR(36)  NOT NULL,
    clause_uuid       VARCHAR(36)  NOT NULL,
    version_number    INT          NOT NULL,
    file_uuid         VARCHAR(36)  NOT NULL COMMENT 'S3 key of the .docx fragment',
    original_filename VARCHAR(500) NULL,
    change_note       TEXT         NULL,
    published_at      TIMESTAMP    NULL COMMENT 'Set when the version becomes the active one; wording is immutable once any document was sent with it',
    published_by      VARCHAR(36)  NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(36)  NULL,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_tcv_clause_version (clause_uuid, version_number),
    CONSTRAINT fk_tcv_clause FOREIGN KEY (clause_uuid) REFERENCES template_clauses (uuid) ON DELETE CASCADE
);

-- 3. Typed parameters on the clause ------------------------------------------
-- Mirrors template_placeholders; registry_field maps a parameter into a
-- first-class Phase 3 registry column (AMOUNT, CURRENCY, VALID_FROM,
-- VALID_TO, EFFECTIVE_DATE), NULL lands in parameters_json only.

CREATE TABLE IF NOT EXISTS template_clause_placeholders (
    uuid             VARCHAR(36)  NOT NULL,
    clause_uuid      VARCHAR(36)  NOT NULL,
    placeholder_key  VARCHAR(100) NOT NULL COMMENT 'Prefixed by clause (e.g. GB_AMOUNT) to avoid collisions with base-template keys',
    label            VARCHAR(255) NOT NULL,
    field_type       VARCHAR(50)  NOT NULL,
    required         TINYINT(1)   NOT NULL DEFAULT 0,
    display_order    INT          NOT NULL DEFAULT 0,
    default_value    TEXT         NULL,
    help_text        TEXT         NULL,
    source           VARCHAR(50)  NOT NULL DEFAULT 'MANUAL',
    source_field     VARCHAR(50)  NULL COMMENT 'Named field the source resolves; NULL = manual entry',
    registry_field   VARCHAR(50)  NULL COMMENT 'AMOUNT / CURRENCY / VALID_FROM / VALID_TO / EFFECTIVE_DATE; NULL = parameters_json only (Phase 3)',
    field_group      VARCHAR(100) NULL,
    validation_rules JSON         NULL,
    select_options   JSON         NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_tcp_clause_key (clause_uuid, placeholder_key),
    CONSTRAINT fk_tcp_clause FOREIGN KEY (clause_uuid) REFERENCES template_clauses (uuid) ON DELETE CASCADE
);

-- 4. Which clauses a template offers -----------------------------------------

CREATE TABLE IF NOT EXISTS template_clause_links (
    uuid          VARCHAR(36) NOT NULL,
    template_uuid VARCHAR(36) NOT NULL,
    clause_uuid   VARCHAR(36) NOT NULL,
    preselected   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT 'Ticked by default in the wizard',
    required      TINYINT(1)  NOT NULL DEFAULT 0 COMMENT 'Always included; the preparer cannot deselect it',
    display_order INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    UNIQUE KEY uq_tcl_template_clause (template_uuid, clause_uuid),
    KEY idx_tcl_clause (clause_uuid),
    CONSTRAINT fk_tcl_template FOREIGN KEY (template_uuid) REFERENCES document_templates (uuid) ON DELETE CASCADE,
    CONSTRAINT fk_tcl_clause FOREIGN KEY (clause_uuid) REFERENCES template_clauses (uuid) ON DELETE CASCADE
);

-- 5. Immutable snapshot of what a sent case contained ------------------------
-- clause_uuid / clause_version_uuid are NULL for a free-text Individuel
-- aftale. The Phase 3 AgreementRecorder reads these rows on COMPLETED.

CREATE TABLE IF NOT EXISTS signing_case_clauses (
    uuid                  VARCHAR(36)  NOT NULL,
    signing_case_id       BIGINT       NOT NULL,
    clause_uuid           VARCHAR(36)  NULL,
    clause_version_uuid   VARCHAR(36)  NULL,
    render_mode           VARCHAR(20)  NOT NULL COMMENT 'As rendered (an INLINE clause may have fallen back to ADDENDUM)',
    parameter_values_json JSON         NULL,
    custom_title          VARCHAR(255) NULL,
    custom_text           TEXT         NULL,
    display_order         INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    KEY idx_scc_case (signing_case_id),
    KEY idx_scc_clause (clause_uuid),
    CONSTRAINT fk_scc_case FOREIGN KEY (signing_case_id) REFERENCES signing_cases (id) ON DELETE CASCADE
);

-- 6. The single shared tillaeg wrapper ---------------------------------------
-- Header/person/company/date placeholders + the {{CLAUSES}} body anchor.
-- When no active shell is uploaded the backend renders a minimal built-in
-- shell, so the composition path never fails on missing ops setup.

CREATE TABLE IF NOT EXISTS clause_addendum_shells (
    uuid              VARCHAR(36)  NOT NULL,
    file_uuid         VARCHAR(36)  NOT NULL COMMENT 'S3 key of the shell .docx',
    original_filename VARCHAR(500) NULL,
    active            TINYINT(1)   NOT NULL DEFAULT 1,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by        VARCHAR(36)  NULL,
    modified_by       VARCHAR(36)  NULL,
    PRIMARY KEY (uuid)
);

-- 7. Dossier draft + frozen snapshot -----------------------------------------
-- clauses_snapshot mirrors the three existing snapshot columns and is
-- mapped updatable=false in JPA; NULL for revisions sent before Phase 2.

ALTER TABLE candidate_dossiers
    ADD COLUMN IF NOT EXISTS clauses_json JSON NULL
        COMMENT 'Ordered array of selected clauses ({clauseUuid, parameterValues, customTitle, customText, displayOrder}) on the draft';

ALTER TABLE candidate_dossier_revisions
    ADD COLUMN IF NOT EXISTS clauses_snapshot JSON NULL
        COMMENT 'Frozen clause selection at Send time; NULL for pre-Phase-2 revisions';

-- 8. Feature flag (default OFF — clause UI ships dark) -----------------------

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES ('documents.clauses.enabled', 'false', 'documents');
