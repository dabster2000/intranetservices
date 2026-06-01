-- ====================================================================
-- V360: Create mobile_device_credential table.
--
-- Purpose: Persists WebAuthn (passkey) device credentials enrolled by
-- the mobile expense app (/expenses/mobile). After a user authenticates
-- via Azure SSO+MFA they may "Trust this device", which registers a
-- platform passkey. The credential's public key is stored here and used
-- later to verify biometric unlocks that re-mint the mobile-session token.
--
-- This table is the datastore behind the Node BFF WebAuthn ceremony
-- (@simplewebauthn/server). Quarkus performs no crypto; it stores and
-- serves credential records and enforces revocation via credential_epoch.
--
-- Design decisions:
--   useruuid is a plain VARCHAR (no FK) to match existing expense tables
--     and survive user-record reorganisation.
--   credential_id is the Base64URL WebAuthn credential id (unique).
--   public_key is the Base64URL-encoded COSE public key (stored as TEXT
--     because length varies by algorithm).
--   sign_count is the WebAuthn signature counter (replay protection).
--   credential_epoch is bumped on revoke; the mobile-session cookie carries
--     the epoch it was minted under and is rejected on refresh if it differs.
--   revoked_at NULL = active; non-NULL = revoked.
--
-- Backwards-compatibility: new table, no existing rows affected.
-- Rollback strategy: DROP TABLE mobile_device_credential;
-- MariaDB 10.x: DATETIME DEFAULT CURRENT_TIMESTAMP and inline INDEX
--   syntax are supported.
-- ====================================================================

CREATE TABLE mobile_device_credential (
  uuid              VARCHAR(36)  NOT NULL
    COMMENT 'UUID primary key for this credential row',
  useruuid          VARCHAR(36)  NOT NULL
    COMMENT 'UUID of the user who enrolled the device',
  credential_id     VARCHAR(255) NOT NULL
    COMMENT 'Base64URL WebAuthn credential id',
  public_key        TEXT         NOT NULL
    COMMENT 'Base64URL-encoded COSE public key for assertion verification',
  sign_count        BIGINT       NOT NULL DEFAULT 0
    COMMENT 'WebAuthn signature counter (replay protection)',
  device_label      VARCHAR(120) NULL
    COMMENT 'Human label for the device, e.g. "iPhone 15"',
  transports        VARCHAR(255) NULL
    COMMENT 'Comma-separated authenticator transports, e.g. "internal,hybrid"',
  credential_epoch  INT          NOT NULL DEFAULT 0
    COMMENT 'Bumped on revoke; mobile-session cookie is rejected if epoch differs',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
    COMMENT 'Enrollment timestamp (UTC)',
  last_used_at      DATETIME     NULL
    COMMENT 'Last successful biometric unlock (UTC)',
  revoked_at        DATETIME     NULL
    COMMENT 'Revocation timestamp; NULL = active',
  PRIMARY KEY (uuid),
  UNIQUE KEY uq_mdc_credential (credential_id),
  INDEX idx_mdc_user (useruuid)
);
