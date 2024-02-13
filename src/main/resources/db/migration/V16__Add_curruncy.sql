alter table invoices add currency varchar(36) default 'DKK' null after invoicenumber;
alter table invoices add vat double default 25 null after currency;