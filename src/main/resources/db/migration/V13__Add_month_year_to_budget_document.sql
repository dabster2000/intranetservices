alter table budget_document
    change month document_date date null;

alter table budget_document
    add year int null after document_date;

alter table budget_document
    add month int null after year;

alter table budget_document
    add day int null after month;