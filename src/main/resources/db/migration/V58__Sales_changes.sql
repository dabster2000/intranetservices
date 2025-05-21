alter table sales_lead
    modify description text null;

alter table sales_lead
    add detailed_description text null after description;

alter table sales_lead
    drop column level;

