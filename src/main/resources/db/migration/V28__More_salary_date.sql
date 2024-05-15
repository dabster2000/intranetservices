alter table salary add type varchar(30) default 'NORMAL' not null after useruuid;
alter table salary add pension_paid double default 2 not null after salary;
create table salary_lump_sum
(
    uuid     varchar(36) not null,
    useruuid varchar(36) not null,
    lump_sum    double  default 0.0   not null,
    pension     boolean default false not null,
    month       date                  not null,
    description varchar(255)          null,
    constraint salary_lump_Sum_pk
        primary key (uuid),
    constraint salary_lump_Sum_user_uuid_fk
        foreign key (useruuid) references user (uuid)
            on delete cascade
) charset = utf8;
create index salary_lump_sum_useruuid_month_index
    on salary_lump_sum (useruuid, month);
create table salary_supplement
(
    uuid         varchar(36)               not null,
    useruuid     varchar(36)               not null,
    type         varchar(10) default 'SUM' not null,
    value        double      default 0.0   not null,
    with_pension boolean     default false not null,
    from_month   date                      not null,
    to_month     date                      not null,
    description  varchar(255)              null,
    constraint salary_supplement_pk
        primary key (uuid),
    constraint salary_supplement_user_uuid_fk
        foreign key (useruuid) references user (uuid)
            on delete cascade
) charset = utf8;



