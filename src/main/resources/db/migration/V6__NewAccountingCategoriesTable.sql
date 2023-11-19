create table accounting_categories
(
    uuid      varchar(36) not null,
    groupname varchar(50) null,
    constraint accounting_categories_pk
        primary key (uuid)
);

alter table accounting_accounts
    add categoryuuid varchar(36) null after companyuuid;

alter table accounting_accounts
    add constraint accounting_accounts_accounting_categories_uuid_fk
        foreign key (categoryuuid) references accounting_categories (uuid);