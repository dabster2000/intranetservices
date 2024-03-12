create table expense_category
(
    uuid          varchar(36)  not null,
    category_name varchar(255) null,
    active        boolean default true      null,
    constraint expense_category_pk
        primary key (uuid)
);

create table expense_account
(
    uuid           varchar(36)  not null,
    companyuuid    varchar(36)  not null,
    expense_category_uuid   varchar(36)  not null,
    account_number int          not null,
    account_name   varchar(255) null,
    active         boolean default true      null,
    constraint expense_account_pk
        primary key (uuid),
    constraint expense_account_expense_category_uuid_fk
        foreign key (expense_category_uuid) references expense_category (uuid)
);

alter table expense_account
    add constraint expense_account_companies_uuid_fk
        foreign key (companyuuid) references companies (uuid)
            on update cascade on delete cascade;

alter table expense_account
    drop foreign key expense_account_expense_category_uuid_fk;

alter table expense_account
    add constraint expense_account_expense_category_uuid_fk
        foreign key (expense_category_uuid) references expense_category (uuid)
            on update cascade on delete cascade;

