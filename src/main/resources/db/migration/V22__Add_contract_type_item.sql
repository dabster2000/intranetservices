create table contract_type_items
(
    id            int auto_increment,
    contractuuid VARCHAR(36)  null,
    `key`         VARCHAR(255) null,
    value         VARCHAR(255) null,
    constraint contract_type_items_pk
        primary key (id)
);
