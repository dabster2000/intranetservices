alter table bi_data_per_day
    modify companyuuid varchar(36) null;

alter table bi_data_per_day
    modify document_date date not null;

alter table bi_data_per_day
    modify year int not null;

alter table bi_data_per_day
    modify month int not null;

alter table bi_data_per_day
    modify day int not null;

alter table bi_data_per_day
    alter column unavailable_hours drop default;

