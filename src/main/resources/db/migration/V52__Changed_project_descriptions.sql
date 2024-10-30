alter table projectdescriptions
    change description purpose text null;

alter table projectdescriptions
    add role text null after purpose;

alter table projectdescriptions
    add learnings text null after role;

alter table projectdescriptions
    change offering roles text null after name;

alter table projectdescriptions
    change tools methods text null after roles;

alter table projectdescriptions
    change `from` active_from date null;

alter table projectdescriptions
    change `to` active_to date null;

alter table projectdescriptions
    change id uuid varchar(36) not null;

alter table projectdescription_users
    change projectdescid projectdesc_uuid varchar(36) not null;

alter table projectdescription_users
    drop column description;
