alter table invoices
    add bonus_consultant varchar(36) null after status;

alter table invoices
    add bonus_consultant_approved boolean default FALSE null after bonus_consultant;

alter table invoiceitems
    add consultantuuid varchar(36) null after uuid;