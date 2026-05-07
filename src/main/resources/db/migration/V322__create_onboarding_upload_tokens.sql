CREATE TABLE onboarding_upload_tokens (
    uuid                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    candidate_uuid        VARCHAR(36)  NULL     COMMENT 'Soft-FK to recruitment_candidates.uuid',
    user_uuid             VARCHAR(36)  NULL     COMMENT 'Soft-FK to users.uuid',
    show_drivers_license  TINYINT(1)   NOT NULL DEFAULT 1,
    show_health_insurance TINYINT(1)   NOT NULL DEFAULT 1,
    show_criminal_record  TINYINT(1)   NOT NULL DEFAULT 1,
    expires_at            TIMESTAMP    NOT NULL,
    created_by_useruuid   VARCHAR(36)  NOT NULL COMMENT 'Soft-FK to users.uuid',
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_out_owner CHECK (
        (candidate_uuid IS NOT NULL AND user_uuid IS NULL) OR
        (candidate_uuid IS NULL     AND user_uuid IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_out_candidate ON onboarding_upload_tokens (candidate_uuid);
CREATE INDEX idx_out_user      ON onboarding_upload_tokens (user_uuid);
