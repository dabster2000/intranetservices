create table sales_coffee_dates
(
    uuid       varchar(36) not null
        primary key,
    useruuid   varchar(36) null,
    bubbleuuid varchar(36) null,
    count      int         not null
);
