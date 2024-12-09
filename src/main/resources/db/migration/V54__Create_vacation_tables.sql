create table vacation
(
    uuid            VARCHAR(36) not null primary key,
    useruuid        varchar(36) not null,
    type            varchar(10) not null,
    date            date        not null,
    vacation_earned DOUBLE      null,
    constraint vacation_aggregate_key
        unique (useruuid, type, date)
);

