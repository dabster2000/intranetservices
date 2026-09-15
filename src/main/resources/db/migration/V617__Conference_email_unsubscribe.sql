-- List/address preferences intentionally have no participant or list foreign-key cascade.
-- Do not backfill either consent flag: absence of withdrawal is not consent.
CREATE TABLE conference_email_suppression (
    uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    conference_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_email VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    unsubscribed_at DATETIME(6) NOT NULL,
    source VARCHAR(24) NOT NULL,
    CONSTRAINT uq_conference_email_suppression UNIQUE (conference_uuid, normalized_email),
    CONSTRAINT ck_conference_suppression_source CHECK (source IN ('PUBLIC_PAGE', 'MAILBOX_ONE_CLICK'))
) ENGINE=InnoDB;

CREATE TABLE conference_unsubscribe_token (
    token_hash BINARY(32) NOT NULL PRIMARY KEY,
    conference_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_email VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    INDEX idx_conference_unsubscribe_address (conference_uuid, normalized_email)
) ENGINE=InnoDB;
