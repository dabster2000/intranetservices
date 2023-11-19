create table accounting_accounts
(
    uuid                varchar(36)       not null,
    companyuuid         varchar(36)       not null,
    account_code        varchar(6)        not null,
    account_description varchar(255)      null,
    shared              boolean default 1 not null,
    constraint accounting_accounts_pk
        primary key (uuid),
    constraint accounting_accounts_companies_uuid_fk
        foreign key (companyuuid) references companies (uuid)
);

create index accounting_accounts_companyuuid_index
    on accounting_accounts (companyuuid);

