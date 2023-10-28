create table notes
(
    id       int         not null   primary key,
    useruuid varchar(36) null,
    notedate date        null,
    type     varchar(30) null,
    content  longtext    null
);