CREATE TABLE onboarding_upload_submissions (
    uuid                     VARCHAR(36)  NOT NULL PRIMARY KEY,
    token_uuid               VARCHAR(36)  NOT NULL COMMENT 'Soft-FK to onboarding_upload_tokens.uuid',
    document_type            ENUM('DRIVERS_LICENSE','HEALTH_INSURANCE','CRIMINAL_RECORD') NOT NULL,
    candidate_uuid           VARCHAR(36)  NULL     COMMENT 'Soft-FK to recruitment_candidates.uuid',
    user_uuid                VARCHAR(36)  NULL     COMMENT 'Soft-FK to users.uuid',
    storage_target           ENUM('S3','SHAREPOINT') NOT NULL,
    s3_file_uuid             VARCHAR(36)  NULL     COMMENT 'fileservice File UUID; set when storage_target=S3',
    sharepoint_drive_item_id VARCHAR(255) NULL     COMMENT 'Microsoft Graph DriveItem.id; set when storage_target=SHAREPOINT',
    sharepoint_web_url       VARCHAR(1024) NULL    COMMENT 'Microsoft Graph DriveItem.webUrl; HR convenience link',
    original_filename        VARCHAR(500) NOT NULL,
    content_type             VARCHAR(100) NOT NULL,
    file_size_bytes          BIGINT       NOT NULL,
    uploaded_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ous_token_doctype (token_uuid, document_type),
    KEY idx_ous_candidate (candidate_uuid),
    KEY idx_ous_user      (user_uuid),
    CONSTRAINT chk_ous_owner CHECK (
        (candidate_uuid IS NOT NULL AND user_uuid IS NULL) OR
        (candidate_uuid IS NULL     AND user_uuid IS NOT NULL)
    ),
    CONSTRAINT chk_ous_storage CHECK (
        (storage_target = 'S3'         AND s3_file_uuid IS NOT NULL) OR
        (storage_target = 'SHAREPOINT' AND sharepoint_drive_item_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
