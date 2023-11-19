alter table company_data
    add companyuuid varchar(36) default 'd8894494-2fb4-4f72-9e05-e6032e6dd691' not null after uuid;

alter table company_data
    add constraint company_data_companies_uuid_fk
        foreign key (companyuuid) references companies (uuid);