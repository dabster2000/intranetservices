create table transportation_registration
(
    uuid        varchar(36)   not null,
    useruuid    varchar(36)   not null,
    date        date          not null,
    purpose     varchar(200)  null,
    destination varchar(200)  not null,
    kilometers  int default 0 not null,
    paid        boolean       not null,
    constraint user_transportation_allowance_pk
        primary key (uuid),
    constraint user_transportation_allowance_user_uuid_fk
        foreign key (useruuid) references user (uuid)
            on delete cascade
) charset utf8;

alter table expenses
    add paid boolean default true null after status;
