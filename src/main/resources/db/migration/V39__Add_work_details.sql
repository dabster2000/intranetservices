alter table work add comments varchar(255) null after workduration;
alter table work add paidout tinyint(1) not null default 1 after billable;