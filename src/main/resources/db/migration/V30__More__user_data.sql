alter table salary drop column pension_paid;

alter table salary modify activefrom date not null after useruuid;
