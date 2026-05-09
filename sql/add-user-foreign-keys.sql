-- ============================================================================
-- add-user-foreign-keys.sql
-- ============================================================================
-- Purpose
--   Add database-enforced FOREIGN KEY constraints from every user-uuid column
--   to user(uuid). Cleans up orphan rows first, aligns column types/collations,
--   truncates fact/BI tables (refresh jobs repopulate them), then adds the FKs.
--
-- Cascade strategy
--   ON DELETE CASCADE for all FKs EXCEPT:
--     - project.userowneruuid                       -> ON DELETE SET NULL
--     - recruitment_candidates.converted_user_uuid  -> ON DELETE SET NULL
--   Rationale: project and candidate history must survive a (rare) user
--   deletion; everything else is per-user data and should follow the user.
--
-- How to run
--   Works in any SQL client (DataGrip, DBeaver, IntelliJ DB plugin, mysql CLI).
--   No stored procedures, no DELIMITER directives - all idempotency is done
--   inline via PREPARE/EXECUTE on dynamic SQL.
--   Example:
--     mysql -h <host> -u <user> -p <db> < add-user-foreign-keys.sql
--
-- Idempotency
--   Every ALTER TABLE is guarded by an INFORMATION_SCHEMA check; re-runs are
--   safe. Cleanup DML is wrapped in a transaction. TRUNCATE on already-empty
--   fact tables is a no-op.
--
-- Atomicity caveat
--   MariaDB auto-commits DDL. The script cannot be rolled back as a whole.
--   If a step fails, fix the issue and re-run; idempotency guards skip work
--   already done.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- Phase 0: Visibility
-- ----------------------------------------------------------------------------
SELECT '=== add-user-foreign-keys.sql starting ===' AS status, NOW() AS at;
SELECT COUNT(*) AS users_in_table FROM user;


-- ----------------------------------------------------------------------------
-- Phase 1: Orphan cleanup (transactional)
-- ----------------------------------------------------------------------------
SELECT '--- Phase 1: orphan cleanup (transactional) ---' AS phase;

START TRANSACTION;

-- 1a. DELETE orphan rows from NOT NULL useruuid columns
DELETE FROM roles
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = roles.useruuid);
SELECT ROW_COUNT() AS deleted_roles;

DELETE FROM userstatus
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = userstatus.useruuid);
SELECT ROW_COUNT() AS deleted_userstatus;

DELETE FROM salary
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = salary.useruuid);
SELECT ROW_COUNT() AS deleted_salary;

DELETE FROM signing_cases
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = signing_cases.user_uuid);
SELECT ROW_COUNT() AS deleted_signing_cases;

DELETE FROM keypurpose
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = keypurpose.useruuid);
SELECT ROW_COUNT() AS deleted_keypurpose;

DELETE FROM cv_tool_employee_cv
WHERE NOT EXISTS (
    SELECT 1 FROM user u
    WHERE u.uuid COLLATE utf8mb4_unicode_ci = cv_tool_employee_cv.useruuid
);
SELECT ROW_COUNT() AS deleted_cv_tool_employee_cv;

DELETE FROM user_contactinfo
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = user_contactinfo.useruuid);
SELECT ROW_COUNT() AS deleted_user_contactinfo;

DELETE FROM user_danlon_history
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = user_danlon_history.useruuid);
SELECT ROW_COUNT() AS deleted_user_danlon_history;

DELETE FROM user_dst_statistics
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = user_dst_statistics.useruuid);
SELECT ROW_COUNT() AS deleted_user_dst_statistics;

DELETE FROM user_ext_account
WHERE NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = user_ext_account.useruuid);
SELECT ROW_COUNT() AS deleted_user_ext_account;

-- 1b. NULL out orphan refs on nullable columns
UPDATE recruitment_candidates
SET converted_user_uuid = NULL
WHERE converted_user_uuid IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user u WHERE u.uuid = recruitment_candidates.converted_user_uuid
  );
SELECT ROW_COUNT() AS nulled_recruitment_candidates_converted;

UPDATE teamroles
SET useruuid = NULL
WHERE useruuid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = teamroles.useruuid);
SELECT ROW_COUNT() AS nulled_teamroles;

UPDATE week
SET useruuid = NULL
WHERE useruuid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = week.useruuid);
SELECT ROW_COUNT() AS nulled_week;

UPDATE employee_data
SET useruuid = NULL
WHERE useruuid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user u WHERE u.uuid = employee_data.useruuid);
SELECT ROW_COUNT() AS nulled_employee_data;

COMMIT;


-- ----------------------------------------------------------------------------
-- Phase 2: Type / collation alignment (idempotent inline)
-- ----------------------------------------------------------------------------
-- Pattern: build the ALTER as a string only if the current column definition
-- doesn't already match the target. If it matches, run a no-op ('DO 0').
-- ----------------------------------------------------------------------------
SELECT '--- Phase 2: type / collation alignment ---' AS phase;

-- alert_dismissals.user_uuid -> VARCHAR(36) general_ci NOT NULL
SET @ddl := IF(
    (SELECT CONCAT(COLUMN_TYPE,'|',COLLATION_NAME,'|',IS_NULLABLE)
     FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'alert_dismissals'
       AND COLUMN_NAME  = 'user_uuid') <> 'varchar(36)|utf8mb4_general_ci|NO',
    'ALTER TABLE `alert_dismissals` MODIFY COLUMN `user_uuid` VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- cv_tool_employee_cv.useruuid -> VARCHAR(36) general_ci NOT NULL
SET @ddl := IF(
    (SELECT CONCAT(COLUMN_TYPE,'|',COLLATION_NAME,'|',IS_NULLABLE)
     FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'cv_tool_employee_cv'
       AND COLUMN_NAME  = 'useruuid') <> 'varchar(36)|utf8mb4_general_ci|NO',
    'ALTER TABLE `cv_tool_employee_cv` MODIFY COLUMN `useruuid` VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- onboarding_upload_tokens.user_uuid -> VARCHAR(36) general_ci NULL
SET @ddl := IF(
    (SELECT CONCAT(COLUMN_TYPE,'|',COLLATION_NAME,'|',IS_NULLABLE)
     FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'onboarding_upload_tokens'
       AND COLUMN_NAME  = 'user_uuid') <> 'varchar(36)|utf8mb4_general_ci|YES',
    'ALTER TABLE `onboarding_upload_tokens` MODIFY COLUMN `user_uuid` VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- onboarding_upload_tokens.created_by_useruuid -> VARCHAR(36) general_ci NOT NULL
SET @ddl := IF(
    (SELECT CONCAT(COLUMN_TYPE,'|',COLLATION_NAME,'|',IS_NULLABLE)
     FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'onboarding_upload_tokens'
       AND COLUMN_NAME  = 'created_by_useruuid') <> 'varchar(36)|utf8mb4_general_ci|NO',
    'ALTER TABLE `onboarding_upload_tokens` MODIFY COLUMN `created_by_useruuid` VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- onboarding_upload_submissions.user_uuid -> VARCHAR(36) general_ci NULL
SET @ddl := IF(
    (SELECT CONCAT(COLUMN_TYPE,'|',COLLATION_NAME,'|',IS_NULLABLE)
     FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'onboarding_upload_submissions'
       AND COLUMN_NAME  = 'user_uuid') <> 'varchar(36)|utf8mb4_general_ci|YES',
    'ALTER TABLE `onboarding_upload_submissions` MODIFY COLUMN `user_uuid` VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- employee_data.useruuid -> VARCHAR(36) general_ci NULL  (was VARCHAR(255))
SET @ddl := IF(
    (SELECT CONCAT(COLUMN_TYPE,'|',COLLATION_NAME,'|',IS_NULLABLE)
     FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'employee_data'
       AND COLUMN_NAME  = 'useruuid') <> 'varchar(36)|utf8mb4_general_ci|YES',
    'ALTER TABLE `employee_data` MODIFY COLUMN `useruuid` VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;


-- ----------------------------------------------------------------------------
-- Phase 3: Truncate fact / BI tables (refresh jobs repopulate)
-- ----------------------------------------------------------------------------
SELECT '--- Phase 3: truncate fact / BI tables ---' AS phase;

-- Note: fact_tw_bonus_annual and fact_tw_bonus_monthly are VIEWs (over the _mat
-- tables), so truncating the _mat tables clears them transitively.
TRUNCATE TABLE fact_user_day;
TRUNCATE TABLE bi_availability_per_day;
TRUNCATE TABLE fact_change_log;
TRUNCATE TABLE fact_tw_bonus_annual_mat;
TRUNCATE TABLE fact_tw_bonus_monthly_mat;


-- ----------------------------------------------------------------------------
-- Phase 4: Add FK constraints (idempotent inline)
-- ----------------------------------------------------------------------------
-- Pattern: only ADD CONSTRAINT if it doesn't already exist. Otherwise no-op.
-- ----------------------------------------------------------------------------
SELECT '--- Phase 4: add FK constraints ---' AS phase;


-- 4a. Two FKs that use ON DELETE SET NULL (preserve history)

-- project.userowneruuid -> user.uuid (SET NULL)
SET @ddl := IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_project_owner_user') = 0,
    'ALTER TABLE `project` ADD CONSTRAINT `fk_project_owner_user` FOREIGN KEY (`userowneruuid`) REFERENCES `user`(`uuid`) ON DELETE SET NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- recruitment_candidates.converted_user_uuid -> user.uuid (SET NULL)
SET @ddl := IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_recruitment_candidates_converted_user') = 0,
    'ALTER TABLE `recruitment_candidates` ADD CONSTRAINT `fk_recruitment_candidates_converted_user` FOREIGN KEY (`converted_user_uuid`) REFERENCES `user`(`uuid`) ON DELETE SET NULL',
    'DO 0'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;


-- 4b. CASCADE FKs (63 of them)

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_alert_dismissals_user')=0, 'ALTER TABLE `alert_dismissals` ADD CONSTRAINT `fk_alert_dismissals_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_cda_uploader_user')=0, 'ALTER TABLE `candidate_dossier_appendices` ADD CONSTRAINT `fk_cda_uploader_user` FOREIGN KEY (`uploaded_by_useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_cdr_sender_user')=0, 'ALTER TABLE `candidate_dossier_revisions` ADD CONSTRAINT `fk_cdr_sender_user` FOREIGN KEY (`sent_by_useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_cko_expense_user')=0, 'ALTER TABLE `cko_expense` ADD CONSTRAINT `fk_cko_expense_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_contract_consultants_user')=0, 'ALTER TABLE `contract_consultants` ADD CONSTRAINT `fk_contract_consultants_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_cv_tool_employee_cv_user')=0, 'ALTER TABLE `cv_tool_employee_cv` ADD CONSTRAINT `fk_cv_tool_employee_cv_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- employee_budget_per_month and employee_work_per_month are VIEWs - no FK possible
SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_employee_data_user')=0, 'ALTER TABLE `employee_data` ADD CONSTRAINT `fk_employee_data_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_expenses_user')=0, 'ALTER TABLE `expenses` ADD CONSTRAINT `fk_expenses_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_experience_consultants_user')=0, 'ALTER TABLE `experience_consultants` ADD CONSTRAINT `fk_experience_consultants_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_fact_budget_day_user')=0, 'ALTER TABLE `fact_budget_day` ADD CONSTRAINT `fk_fact_budget_day_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_fact_change_log_user')=0, 'ALTER TABLE `fact_change_log` ADD CONSTRAINT `fk_fact_change_log_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- fact_salary_monthly and fact_salary_monthly_teamroles are VIEWs - no FK possible
-- fact_tw_bonus_annual and fact_tw_bonus_monthly are VIEWs - no FK possible
-- The _mat tables are real tables and DO get FKs.

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_fact_sick_day_rolling_mat_user')=0, 'ALTER TABLE `fact_sick_day_rolling_mat` ADD CONSTRAINT `fk_fact_sick_day_rolling_mat_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_fact_tw_bonus_annual_mat_user')=0, 'ALTER TABLE `fact_tw_bonus_annual_mat` ADD CONSTRAINT `fk_fact_tw_bonus_annual_mat_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_fact_tw_bonus_monthly_mat_user')=0, 'ALTER TABLE `fact_tw_bonus_monthly_mat` ADD CONSTRAINT `fk_fact_tw_bonus_monthly_mat_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_fact_user_day_user')=0, 'ALTER TABLE `fact_user_day` ADD CONSTRAINT `fk_fact_user_day_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_bi_availability_per_day_user')=0, 'ALTER TABLE `bi_availability_per_day` ADD CONSTRAINT `fk_bi_availability_per_day_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_invoice_bonuses_user')=0, 'ALTER TABLE `invoice_bonuses` ADD CONSTRAINT `fk_invoice_bonuses_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_invoice_bonus_eligibility_user')=0, 'ALTER TABLE `invoice_bonus_eligibility` ADD CONSTRAINT `fk_invoice_bonus_eligibility_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_itbudget_user')=0, 'ALTER TABLE `itbudget` ADD CONSTRAINT `fk_itbudget_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_keypurpose_user')=0, 'ALTER TABLE `keypurpose` ADD CONSTRAINT `fk_keypurpose_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_lessons_user')=0, 'ALTER TABLE `lessons` ADD CONSTRAINT `fk_lessons_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_month_submission_user')=0, 'ALTER TABLE `month_submission` ADD CONSTRAINT `fk_month_submission_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_news_birthday_user')=0, 'ALTER TABLE `news` ADD CONSTRAINT `fk_news_birthday_user` FOREIGN KEY (`birthdayuseruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_notes_user')=0, 'ALTER TABLE `notes` ADD CONSTRAINT `fk_notes_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_onboarding_upload_submissions_user')=0, 'ALTER TABLE `onboarding_upload_submissions` ADD CONSTRAINT `fk_onboarding_upload_submissions_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_onboarding_upload_tokens_user')=0, 'ALTER TABLE `onboarding_upload_tokens` ADD CONSTRAINT `fk_onboarding_upload_tokens_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_onboarding_upload_tokens_creator_user')=0, 'ALTER TABLE `onboarding_upload_tokens` ADD CONSTRAINT `fk_onboarding_upload_tokens_creator_user` FOREIGN KEY (`created_by_useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_passwordchanges_user')=0, 'ALTER TABLE `passwordchanges` ADD CONSTRAINT `fk_passwordchanges_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_projectdescription_users_user')=0, 'ALTER TABLE `projectdescription_users` ADD CONSTRAINT `fk_projectdescription_users_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_questionnaire_submission_user')=0, 'ALTER TABLE `questionnaire_submission` ADD CONSTRAINT `fk_questionnaire_submission_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_recruitment_candidates_creator_user')=0, 'ALTER TABLE `recruitment_candidates` ADD CONSTRAINT `fk_recruitment_candidates_creator_user` FOREIGN KEY (`created_by_useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_roles_user')=0, 'ALTER TABLE `roles` ADD CONSTRAINT `fk_roles_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_salary_user')=0, 'ALTER TABLE `salary` ADD CONSTRAINT `fk_salary_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_salary_lump_sum_user')=0, 'ALTER TABLE `salary_lump_sum` ADD CONSTRAINT `fk_salary_lump_sum_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_salary_supplement_user')=0, 'ALTER TABLE `salary_supplement` ADD CONSTRAINT `fk_salary_supplement_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_sales_coffee_dates_user')=0, 'ALTER TABLE `sales_coffee_dates` ADD CONSTRAINT `fk_sales_coffee_dates_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_sales_lead_consultant_user')=0, 'ALTER TABLE `sales_lead_consultant` ADD CONSTRAINT `fk_sales_lead_consultant_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_signing_cases_user')=0, 'ALTER TABLE `signing_cases` ADD CONSTRAINT `fk_signing_cases_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_taskboard_item_workers_user')=0, 'ALTER TABLE `taskboard_item_workers` ADD CONSTRAINT `fk_taskboard_item_workers_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_teamroles_user')=0, 'ALTER TABLE `teamroles` ADD CONSTRAINT `fk_teamroles_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_transportation_registration_user')=0, 'ALTER TABLE `transportation_registration` ADD CONSTRAINT `fk_transportation_registration_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_userstatus_user')=0, 'ALTER TABLE `userstatus` ADD CONSTRAINT `fk_userstatus_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_activity_user')=0, 'ALTER TABLE `user_activity` ADD CONSTRAINT `fk_user_activity_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_bank_info_user')=0, 'ALTER TABLE `user_bank_info` ADD CONSTRAINT `fk_user_bank_info_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_certifications_user')=0, 'ALTER TABLE `user_certifications` ADD CONSTRAINT `fk_user_certifications_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_contactinfo_user')=0, 'ALTER TABLE `user_contactinfo` ADD CONSTRAINT `fk_user_contactinfo_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_contract_bonus_user')=0, 'ALTER TABLE `user_contract_bonus` ADD CONSTRAINT `fk_user_contract_bonus_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_danlon_history_user')=0, 'ALTER TABLE `user_danlon_history` ADD CONSTRAINT `fk_user_danlon_history_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_dst_statistics_user')=0, 'ALTER TABLE `user_dst_statistics` ADD CONSTRAINT `fk_user_dst_statistics_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_ext_account_user')=0, 'ALTER TABLE `user_ext_account` ADD CONSTRAINT `fk_user_ext_account_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_pension_user')=0, 'ALTER TABLE `user_pension` ADD CONSTRAINT `fk_user_pension_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_resume_user')=0, 'ALTER TABLE `user_resume` ADD CONSTRAINT `fk_user_resume_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_session_user')=0, 'ALTER TABLE `user_session` ADD CONSTRAINT `fk_user_session_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_user_settings_user')=0, 'ALTER TABLE `user_settings` ADD CONSTRAINT `fk_user_settings_user` FOREIGN KEY (`user_uuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_vacation_user')=0, 'ALTER TABLE `vacation` ADD CONSTRAINT `fk_vacation_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_week_user')=0, 'ALTER TABLE `week` ADD CONSTRAINT `fk_week_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_work_user')=0, 'ALTER TABLE `work` ADD CONSTRAINT `fk_work_user` FOREIGN KEY (`useruuid`) REFERENCES `user`(`uuid`) ON DELETE CASCADE', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;


-- ----------------------------------------------------------------------------
-- Phase 5: Verification
-- ----------------------------------------------------------------------------
SELECT '--- Phase 5: verification ---' AS phase;

-- Total user-FKs in the database (incl. the 12 that already existed before)
SELECT COUNT(*) AS total_user_fks_in_db
FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA   = DATABASE()
  AND REFERENCED_TABLE_NAME = 'user';

-- The 59 FKs added by this script - expect 59
-- (6 of the 65 originally listed targeted VIEWs and were dropped:
--  fact_salary_monthly, fact_salary_monthly_teamroles,
--  fact_tw_bonus_annual, fact_tw_bonus_monthly,
--  employee_budget_per_month, employee_work_per_month)
SELECT COUNT(*) AS fks_added_by_script
FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND CONSTRAINT_NAME IN (
      'fk_project_owner_user',
      'fk_recruitment_candidates_converted_user',
      'fk_alert_dismissals_user',
      'fk_cda_uploader_user',
      'fk_cdr_sender_user',
      'fk_cko_expense_user',
      'fk_contract_consultants_user',
      'fk_cv_tool_employee_cv_user',
      'fk_employee_data_user',
      'fk_expenses_user',
      'fk_experience_consultants_user',
      'fk_fact_budget_day_user',
      'fk_fact_change_log_user',
      'fk_fact_sick_day_rolling_mat_user',
      'fk_fact_tw_bonus_annual_mat_user',
      'fk_fact_tw_bonus_monthly_mat_user',
      'fk_fact_user_day_user',
      'fk_bi_availability_per_day_user',
      'fk_invoice_bonuses_user',
      'fk_invoice_bonus_eligibility_user',
      'fk_itbudget_user',
      'fk_keypurpose_user',
      'fk_lessons_user',
      'fk_month_submission_user',
      'fk_news_birthday_user',
      'fk_notes_user',
      'fk_onboarding_upload_submissions_user',
      'fk_onboarding_upload_tokens_user',
      'fk_onboarding_upload_tokens_creator_user',
      'fk_passwordchanges_user',
      'fk_projectdescription_users_user',
      'fk_questionnaire_submission_user',
      'fk_recruitment_candidates_creator_user',
      'fk_roles_user',
      'fk_salary_user',
      'fk_salary_lump_sum_user',
      'fk_salary_supplement_user',
      'fk_sales_coffee_dates_user',
      'fk_sales_lead_consultant_user',
      'fk_signing_cases_user',
      'fk_taskboard_item_workers_user',
      'fk_teamroles_user',
      'fk_transportation_registration_user',
      'fk_userstatus_user',
      'fk_user_activity_user',
      'fk_user_bank_info_user',
      'fk_user_certifications_user',
      'fk_user_contactinfo_user',
      'fk_user_contract_bonus_user',
      'fk_user_danlon_history_user',
      'fk_user_dst_statistics_user',
      'fk_user_ext_account_user',
      'fk_user_pension_user',
      'fk_user_resume_user',
      'fk_user_session_user',
      'fk_user_settings_user',
      'fk_vacation_user',
      'fk_week_user',
      'fk_work_user'
  );

SELECT '=== add-user-foreign-keys.sql finished ===' AS status, NOW() AS at;
