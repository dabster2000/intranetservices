create table user_bank_info
(
    uuid        varchar(36) not null,
    useruuid    varchar(36) not null,
    active_date date        not null,
    regnr       int         null,
    account_nr  int         null,
    bic_swift   int         null,
    iban        int         null,
    constraint user_bank_info_pk
        primary key (uuid),
    constraint user_bank_info_user_uuid_fk
        foreign key (useruuid) references user (uuid)
            on delete cascade
) charset utf8;

create unique index user_bank_info_active_date_useruuid_uindex
    on user_bank_info (active_date, useruuid);

alter table useraccount
    change account economics int not null;

rename table useraccount to user_ext_account;

alter table user_ext_account
    add danlon int null after economics;

create table user_pension
(
    uuid            varchar(36)        not null,
    useruuid        varchar(36)        not null,
    active_date     date               not null,
    pension_own     double default 0.0 not null,
    pension_company double default 0.0 not null,
    constraint user_pension_pk
        primary key (uuid),
    constraint user_pension_user_uuid_fk
        foreign key (useruuid) references user (uuid)
            on delete cascade
) charset utf8;

create table user_dst_statistics
(
    uuid                 varchar(36)                       not null,
    useruuid             varchar(36)                       not null,
    active_date          date                              not null,
    employement_type     varchar(20) default 'NOT_LIMITED' not null comment 'Ansættelse i virksomheden, tidsbegrænset eller ikke tidsbegrænset',
    employement_terms    varchar(30) default 'FUNKTIONAER' not null comment 'Ansættelsesvilkår, funktionær',
    employement_function int         default 251210        not null comment 'Arbejdsfunktion (DISCO-08)',
    job_status           varchar(50)                       not null comment 'Jobstatus',
    salary_type          varchar(10)                       not null comment 'Aflønningsform, fastløn eller timeløn',
    constraint user_dst_statistics_pk
        primary key (uuid),
    constraint user_dst_statistics_user_uuid_fk
        foreign key (useruuid) references user (uuid)
            on delete cascade
) charset utf8;



