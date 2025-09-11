-- BONUS-TABEL
CREATE TABLE IF NOT EXISTS invoice_bonuses (
                                               uuid            VARCHAR(36) NOT NULL,

    -- SKAL matche invoices.uuid (varchar(40), latin1_swedish_ci)
                                               invoiceuuid     VARCHAR(40)
                                                                   CHARACTER SET latin1
                                                                   COLLATE latin1_swedish_ci
                                                                           NOT NULL,

    -- SKAL matche user.uuid (varchar(36), utf8mb3_general_ci)
                                               useruuid        VARCHAR(36)
                                                                   CHARACTER SET utf8mb3
                                                                   COLLATE utf8mb3_general_ci
                                                                           NOT NULL,

                                               share_type      VARCHAR(16) NOT NULL,          -- 'PERCENT' | 'AMOUNT'
                                               share_value     DOUBLE      NOT NULL,
                                               computed_amount DOUBLE      NOT NULL DEFAULT 0,
                                               status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING|APPROVED|REJECTED
                                               override_note   TEXT        NULL,

    -- FK til `user`. Skal også være utf8mb3/utf8mb3_general_ci
                                               added_by        VARCHAR(36)
                                                                   CHARACTER SET utf8mb3
                                                                   COLLATE utf8mb3_general_ci
                                                                           NOT NULL,

                                               approved_by     VARCHAR(36)
                                                                   CHARACTER SET utf8mb3
                                                                   COLLATE utf8mb3_general_ci
                                                                           NULL,

                                               created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                               approved_at     DATETIME    NULL,

                                               CONSTRAINT pk_invoice_bonuses PRIMARY KEY (uuid),

    -- Eksplicitte indeks på FK-kolonner (god skik)
                                               KEY idx_invbon_invoiceuuid (invoiceuuid),
                                               KEY idx_invbon_useruuid    (useruuid),
                                               KEY idx_invbon_added_by    (added_by),
                                               KEY idx_invbon_approved_by (approved_by),

                                               CONSTRAINT fk_invbonus_invoice
                                                   FOREIGN KEY (invoiceuuid) REFERENCES invoices (uuid) ON DELETE CASCADE,

                                               CONSTRAINT fk_invbonus_user
                                                   FOREIGN KEY (useruuid)   REFERENCES `user` (uuid) ON DELETE CASCADE,

                                               CONSTRAINT fk_invbonus_addedby
                                                   FOREIGN KEY (added_by)   REFERENCES `user` (uuid),

                                               CONSTRAINT fk_invbonus_apprby
                                                   FOREIGN KEY (approved_by) REFERENCES `user` (uuid),

                                               CONSTRAINT ux_invoice_bonuses_invoice_user UNIQUE (invoiceuuid, useruuid),

                                               CONSTRAINT chk_share_type CHECK (share_type IN ('PERCENT','AMOUNT')),
                                               CONSTRAINT chk_status     CHECK (status IN ('PENDING','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


-- WHITELIST/ELIGIBILITY (useruuid skal matche `user`.uuid)
CREATE TABLE IF NOT EXISTS invoice_bonus_eligibility (
                                                         uuid            VARCHAR(36) NOT NULL,

                                                         useruuid        VARCHAR(36)
                                                                             CHARACTER SET utf8mb3
                                                                             COLLATE utf8mb3_general_ci
                                                                                     NOT NULL UNIQUE,

                                                         can_self_assign TINYINT(1)  NOT NULL DEFAULT 0,
                                                         active_from     DATE        NOT NULL DEFAULT '2000-01-01',
                                                         active_to       DATE        NOT NULL DEFAULT '2999-12-31',

                                                         CONSTRAINT pk_inv_bonus_elig PRIMARY KEY (uuid),
                                                         CONSTRAINT fk_inv_bonus_elig_user FOREIGN KEY (useruuid) REFERENCES `user` (uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;