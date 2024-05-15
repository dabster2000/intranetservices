alter table user_bank_info
    modify regnr VARCHAR(4) null;

alter table user_bank_info
    modify account_nr VARCHAR(10) null;

alter table user_bank_info
    modify bic_swift VARCHAR(30) null;

alter table user_bank_info
    modify iban VARCHAR(30) null;
