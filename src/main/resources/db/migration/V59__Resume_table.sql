create table user_resume
(
    uuid          varchar(36) not null
        primary key,
    useruuid      varchar(36) not null,
    resume_eng    mediumtext  null,
    resume_dk     mediumtext  null,
    resume_result longtext    null
);