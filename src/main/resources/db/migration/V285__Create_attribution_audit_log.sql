-- V285__Create_attribution_audit_log.sql
-- Audit trail for post-creation attribution edits and AI resolutions

CREATE TABLE attribution_audit_log (
    uuid          VARCHAR(36)  NOT NULL,
    invoice_uuid  VARCHAR(36)  NOT NULL,
    item_uuid     VARCHAR(40)  NOT NULL,
    changed_by    VARCHAR(36)  NOT NULL,
    change_type   VARCHAR(20)  NOT NULL,
    old_state     JSON         NOT NULL,
    new_state     JSON         NOT NULL,
    reason        VARCHAR(500) NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (uuid),
    INDEX idx_audit_invoice (invoice_uuid),
    INDEX idx_audit_item (item_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
