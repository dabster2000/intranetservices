alter table budget_document
    add companyuuid varchar(36) null;

alter table budget_document
    add constraint budget_document_companies_uuid_fk
        foreign key (companyuuid) references companies (uuid);