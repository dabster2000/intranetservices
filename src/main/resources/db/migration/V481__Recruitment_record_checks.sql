-- V481: ISAE 3000 criminal-record sampling.
--
-- Every candidate entering the OFFER stage gets exactly ONE draw row —
-- selected or not — at the sample rate configured in app_settings
-- ('recruitment.record-check.sample-rate', percent, default 20). The row
-- IS the audit trail: rate_applied pins which rate governed the draw,
-- selection is deterministic (SHA-256 of the candidate uuid mod 100), and
-- the outcome fields record the human verification WITHOUT ever storing
-- the attest itself (data minimization — outcome only, decided 2026-08-09).
-- Rows survive candidate anonymization pseudonymized: the FK RESTRICTs
-- deletion and the anonymizer redacts the candidate row in place.

CREATE TABLE recruitment_record_checks (
    uuid             VARCHAR(36) NOT NULL,
    candidate_uuid   VARCHAR(36) NOT NULL
        COMMENT 'One draw per candidate — the idempotency key',
    application_uuid VARCHAR(36) NULL
        COMMENT 'The application whose OFFER entry triggered the draw',
    drawn_at         DATETIME    NOT NULL,
    rate_applied     INT         NOT NULL
        COMMENT 'Sample rate (percent) in force at draw time',
    selected         TINYINT(1)  NOT NULL
        COMMENT '1 = candidate must present a clean straffeattest',
    outcome          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING | VERIFIED_CLEAN | NOT_CLEAN (PENDING forever when not selected)',
    verified_by      VARCHAR(36) NULL
        COMMENT 'users.uuid of the HR user who saw the attest',
    verified_at      DATETIME    NULL,

    -- Audit columns (house Auditable pattern, V421)
    created_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    updated_at DATETIME NOT NULL COMMENT 'Set by AuditEntityListener',
    created_by VARCHAR(36) NOT NULL COMMENT 'users.uuid from X-Requested-By',
    modified_by VARCHAR(36) NULL COMMENT 'users.uuid from X-Requested-By',

    PRIMARY KEY (uuid),
    UNIQUE KEY uq_rrc_candidate (candidate_uuid),
    KEY idx_rrc_selected_outcome (selected, outcome),
    CONSTRAINT chk_rrc_outcome_enum
        CHECK (outcome IN ('PENDING','VERIFIED_CLEAN','NOT_CLEAN')),
    CONSTRAINT fk_rrc_candidate
        FOREIGN KEY (candidate_uuid) REFERENCES recruitment_candidates (uuid) ON DELETE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='ISAE 3000 random-sample criminal-record checks (outcome only, never the attest)';
