alter table invoices
    modify bonus_consultant_approved int default 0 null;

alter table invoices
    add bonus_override_amount double default 0 null after bonus_consultant_approved;