alter table meal_plan drop column menu_pdf;

alter table meal_plan_user
    change id uuid char(36) not null;

alter table meal_plan_user
    change meal_plan_id meal_plan_uuid char(36) not null;

alter table meal_plan_user
    add meal_choice_uuid varchar(36) not null;

alter table meal_choice
    change id uuid char(36) not null;

alter table meal_choice
    change meal_plan_user_id meal_plan_user_uuid char(36) not null;

alter table meal_choice
    modify weekday varchar(50) not null;

alter table meal_plan_buffer
    change id uuid char(36) not null;

alter table meal_plan_buffer
    change meal_plan_id meal_plan_uuid char(36) not null;

alter table meal_plan_buffer
    add buffer_uuid varchar(36) not null;

alter table buffer
    change id uuid char(36) not null;

alter table buffer
    change meal_plan_buffer_id meal_plan_buffer_uuid char(36) not null;

alter table buffer
    modify weekday varchar(50) not null;

