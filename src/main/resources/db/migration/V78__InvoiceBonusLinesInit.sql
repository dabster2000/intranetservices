CREATE TABLE IF NOT EXISTS invoice_bonus_lines (
                                                   uuid             VARCHAR(36)  NOT NULL,
                                                   bonusuuid        VARCHAR(36)  NOT NULL,
                                                   invoiceuuid      VARCHAR(40)  NOT NULL,
                                                   invoiceitemuuid  VARCHAR(36)  NOT NULL,
                                                   percentage       DOUBLE       NOT NULL,   -- 0..100
                                                   created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                   updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

                                                   CONSTRAINT pk_invbonus_lines PRIMARY KEY (uuid),
                                                   CONSTRAINT ux_invbonusline_bonus_item UNIQUE (bonusuuid, invoiceitemuuid),

                                                   KEY idx_invbonus_lines_bonus (bonusuuid),
                                                   KEY idx_invbonus_lines_item  (invoiceitemuuid),

                                                   CONSTRAINT fk_invbonus_lines_bonus
                                                       FOREIGN KEY (bonusuuid) REFERENCES invoice_bonuses (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;