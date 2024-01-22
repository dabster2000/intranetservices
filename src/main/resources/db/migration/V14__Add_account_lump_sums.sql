CREATE TABLE `accounting_lump_sums` (
                                        `uuid` varchar(36) NOT NULL,
                                        `accounting_account_uuid` varchar(36) NOT NULL,
                                        `description` varchar(255) DEFAULT NULL,
                                        `amount` double NOT NULL DEFAULT '0',
                                        `registered_date` date NOT NULL,
                                        PRIMARY KEY (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

alter table accounting_lump_sums
    add constraint accounting_lump_sums_accounting_accounts_uuid_fk
        foreign key (accounting_account_uuid) references accounting_accounts (uuid);