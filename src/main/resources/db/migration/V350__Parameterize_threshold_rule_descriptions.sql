-- ====================================================================
-- V350: Parameterize AI threshold rule descriptions.
--
-- Moves the three numeric thresholds that already exist in
-- ai_validation_parameter out of persisted rule prose and into
-- {{parameter_key}} placeholders. The rendered OpenAI prompt remains
-- equivalent as long as seeded parameter values are unchanged.
--
-- No ai_config_history row is written; this is a storage-format migration,
-- not an admin-authored policy change.
-- ====================================================================

DROP PROCEDURE IF EXISTS _tmp_validate_ai_threshold_parameters;

DELIMITER $$
CREATE PROCEDURE _tmp_validate_ai_threshold_parameters()
BEGIN
    DECLARE v_param_count INT DEFAULT 0;
    DECLARE v_rule_count INT DEFAULT 0;
    DECLARE v_unexpected_rule_count INT DEFAULT 0;

    SELECT COUNT(*) INTO v_param_count
    FROM ai_validation_parameter
    WHERE parameter_key IN (
        'meal_cost_per_person_dkk',
        'it_equipment_pre_approval_dkk',
        'date_mismatch_tolerance_days'
    );

    IF v_param_count <> 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V350 abort: missing required AI threshold parameter rows';
    END IF;

    SELECT COUNT(*) INTO v_rule_count
    FROM ai_rule_catalog
    WHERE rule_id IN (
        'R_MEAL_COST_PER_PERSON',
        'R_IT_EQUIPMENT_LIMIT',
        'R_DATE_MISMATCH'
    );

    IF v_rule_count <> 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V350 abort: missing required AI threshold rule rows';
    END IF;

    SELECT COUNT(*) INTO v_unexpected_rule_count
    FROM ai_rule_catalog
    WHERE (rule_id = 'R_MEAL_COST_PER_PERSON'
           AND description NOT IN (
               'Food or drink above 125 DKK per person requires a documented business reason.',
               'Food or drink above {{meal_cost_per_person_dkk}} DKK per person requires a documented business reason.'
           ))
       OR (rule_id = 'R_IT_EQUIPMENT_LIMIT'
           AND description NOT IN (
               'IT equipment purchases above 500 DKK require pre-approval.',
               'IT equipment purchases above {{it_equipment_pre_approval_dkk}} DKK require pre-approval.'
           ))
       OR (rule_id = 'R_DATE_MISMATCH'
           AND description NOT IN (
               'Receipt date and expense date must be within 30 calendar days of each other.',
               'Receipt date and expense date must be within {{date_mismatch_tolerance_days}} calendar days of each other.'
           ));

    IF v_unexpected_rule_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V350 abort: threshold rule descriptions were manually changed; parameterize them by hand';
    END IF;
END$$
DELIMITER ;

CALL _tmp_validate_ai_threshold_parameters();
DROP PROCEDURE _tmp_validate_ai_threshold_parameters;

UPDATE ai_rule_catalog
SET description = 'Food or drink above {{meal_cost_per_person_dkk}} DKK per person requires a documented business reason.',
    updated_at  = NOW(3),
    updated_by  = 'SYSTEM_SEED'
WHERE rule_id = 'R_MEAL_COST_PER_PERSON';

UPDATE ai_rule_catalog
SET description = 'IT equipment purchases above {{it_equipment_pre_approval_dkk}} DKK require pre-approval.',
    updated_at  = NOW(3),
    updated_by  = 'SYSTEM_SEED'
WHERE rule_id = 'R_IT_EQUIPMENT_LIMIT';

UPDATE ai_rule_catalog
SET description = 'Receipt date and expense date must be within {{date_mismatch_tolerance_days}} calendar days of each other.',
    updated_at  = NOW(3),
    updated_by  = 'SYSTEM_SEED'
WHERE rule_id = 'R_DATE_MISMATCH';
