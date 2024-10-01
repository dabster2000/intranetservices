ALTER TABLE `expenses` ADD COLUMN `paid_datetime` DATETIME DEFAULT NULL;

UPDATE `expenses`
SET `paid_datetime` = CASE
                          WHEN `paid` = 1 THEN DATE_FORMAT(`datecreated`, '%Y-%m-20')
                          ELSE NULL
    END;

ALTER TABLE `expenses` DROP COLUMN `paid`;

ALTER TABLE `expenses` CHANGE COLUMN `paid_datetime` `paid_out` DATETIME;


ALTER TABLE `work` ADD COLUMN `paid_datetime` DATETIME DEFAULT NULL;

UPDATE `work`
SET `paid_datetime` = CASE
                          WHEN `paidout` = 1 THEN DATE_FORMAT(`registered`, '%Y-%m-20')
                          ELSE NULL
    END;

ALTER TABLE `work` DROP COLUMN `paidout`;

ALTER TABLE `work` CHANGE COLUMN `paid_datetime` `paid_out` DATETIME;

ALTER TABLE `transportation_registration` ADD COLUMN `paid_datetime` DATETIME DEFAULT NULL;

UPDATE `transportation_registration`
SET `paid_datetime` = CASE
                          WHEN `paid` = 1 THEN DATE_FORMAT(`date`, '%Y-%m-20')
                          ELSE NULL
    END;

ALTER TABLE `transportation_registration` DROP COLUMN `paid`;

ALTER TABLE `transportation_registration` CHANGE COLUMN `paid_datetime` `paid_out` DATETIME;