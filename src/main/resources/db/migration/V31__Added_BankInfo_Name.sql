alter table user_bank_info add fullname varchar(80) null after useruuid;
alter table user_bank_info modify useruuid varchar(36) null;