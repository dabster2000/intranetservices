alter table sales_lead
    add salesleaduuid varchar(36) null after uuid;
alter table sales_lead
    add child_lead boolean default 0 not null after salesleaduuid;
alter table sales_lead add constraint FKgyfawyl3btqtxp3p9gyypnqph foreign key (salesleaduuid) references sales_lead (uuid);