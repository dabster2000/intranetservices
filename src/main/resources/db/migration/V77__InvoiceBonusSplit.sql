-- 1) Ny tabel til bonusser
CREATE TABLE IF NOT EXISTS invoice_bonuses (
                                               uuid              VARCHAR(36)  NOT NULL,
                                               invoiceuuid       VARCHAR(40)  NOT NULL,
                                               useruuid          VARCHAR(36)  NOT NULL,
                                               share_type        VARCHAR(16)  NOT NULL,          -- 'PERCENT' | 'AMOUNT'
                                               share_value       DOUBLE       NOT NULL,
                                               computed_amount   DOUBLE       NOT NULL DEFAULT 0,
                                               status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING|APPROVED|REJECTED
                                               override_note     TEXT         NULL,
                                               added_by          VARCHAR(36)  NOT NULL,
                                               approved_by       VARCHAR(36)  NULL,
                                               created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                               approved_at       DATETIME     NULL,

                                               CONSTRAINT pk_invoice_bonuses PRIMARY KEY (uuid),
                                               CONSTRAINT fk_invbonus_invoice FOREIGN KEY (invoiceuuid) REFERENCES invoices(uuid) ON DELETE CASCADE,
                                               CONSTRAINT fk_invbonus_user    FOREIGN KEY (useruuid)   REFERENCES user(uuid)     ON DELETE CASCADE,
                                               CONSTRAINT fk_invbonus_addedby FOREIGN KEY (added_by)   REFERENCES user(uuid),
                                               CONSTRAINT fk_invbonus_apprby  FOREIGN KEY (approved_by)REFERENCES user(uuid),
                                               CONSTRAINT ux_invoice_bonuses_invoice_user UNIQUE (invoiceuuid, useruuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 2) Whitelist/eligibility
CREATE TABLE IF NOT EXISTS invoice_bonus_eligibility (
                                                         uuid            VARCHAR(36) NOT NULL,
                                                         useruuid        VARCHAR(36) NOT NULL UNIQUE,
                                                         can_self_assign TINYINT(1)  NOT NULL DEFAULT 0,
                                                         active_from     DATE        NOT NULL DEFAULT '2000-01-01',
                                                         active_to       DATE        NOT NULL DEFAULT '2999-12-31',
                                                         CONSTRAINT pk_inv_bonus_elig PRIMARY KEY (uuid),
                                                         CONSTRAINT fk_inv_bonus_elig_user FOREIGN KEY (useruuid) REFERENCES user(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 3) Migration af eksisterende data fra invoices.* felter
--    Mappes til én bonusrække pr. faktura hvis bonus_consultant er sat
INSERT INTO invoice_bonuses (
    uuid, invoiceuuid, useruuid, share_type, share_value, computed_amount, status, override_note, added_by, created_at, updated_at
)
SELECT
    UUID(), i.uuid, i.bonus_consultant,
    'AMOUNT',
    IFNULL(i.bonus_override_amount, 0),
    IFNULL(i.bonus_override_amount, 0),
    CASE i.bonus_consultant_approved
        WHEN 1 THEN 'APPROVED'
        WHEN 2 THEN 'REJECTED'
        ELSE 'PENDING'
        END,
    i.bonus_override_note,
    COALESCE(i.bonus_consultant, 'system'),
    NOW(), NOW()
FROM invoices i
WHERE i.bonus_consultant IS NOT NULL AND i.bonus_consultant <> '';

-- 4) (Valgfrit) ryd de gamle felter så UI ikke viser forældet data
--    Vi beholder kolonnerne i første release for bagudkompatibilitet.
UPDATE invoices
SET bonus_consultant = NULL,
    bonus_override_amount = 0,
    bonus_override_note = NULL,
    bonus_consultant_approved = 0
WHERE bonus_consultant IS NOT NULL;

-- Note: Du kan i en senere Flyway version fjerne kolonnerne helt:
-- ALTER TABLE invoices
--   DROP COLUMN bonus_consultant,
--   DROP COLUMN bonus_consultant_approved,
--   DROP COLUMN bonus_override_amount,
--   DROP COLUMN bonus_override_note;
