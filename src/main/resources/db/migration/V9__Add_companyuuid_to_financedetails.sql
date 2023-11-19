alter table finance_details
    add companyuuid varchar(36) default 'd8894494-2fb4-4f72-9e05-e6032e6dd691' not null after id;

alter table finance_details
    add constraint finance_details_companies_uuid_fk
        foreign key (companyuuid) references companies (uuid);

create table integration_keys
(
    uuid        varchar(36)  not null,
    companyuuid varchar(36)  not null,
    `key`       varchar(255) null,
    value       varchar(255) null,
    constraint integration_keys_pk
        primary key (uuid),
    constraint integration_keys_companies_uuid_fk
        foreign key (companyuuid) references companies (uuid)
            on delete cascade
);
