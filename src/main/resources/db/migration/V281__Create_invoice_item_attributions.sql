CREATE TABLE invoice_item_attributions (
    uuid              VARCHAR(36)    NOT NULL,
    invoiceitem_uuid  VARCHAR(40)    NOT NULL,
    consultant_uuid   VARCHAR(36)    NOT NULL,
    share_pct         DECIMAL(7,4)   NOT NULL,
    attributed_amount DECIMAL(12,2)  NOT NULL,
    original_hours    DECIMAL(8,2)   NULL,
    source            VARCHAR(10)    NOT NULL DEFAULT 'AUTO',
    created_at        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    INDEX idx_iia_item (invoiceitem_uuid),
    INDEX idx_iia_consultant (consultant_uuid),
    UNIQUE KEY uq_iia_item_consultant (invoiceitem_uuid, consultant_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
