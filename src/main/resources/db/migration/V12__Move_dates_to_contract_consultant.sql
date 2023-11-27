alter table contract_consultants
    add activefrom date null after useruuid;

alter table contract_consultants
    add activeto date null after activefrom;

alter table contract_consultants
    drop column budget;