-- Seed database with existing hardcoded contract types and pricing rules
-- This provides a baseline for testing and allows gradual migration from enum-based system

-- Insert contract type definitions
INSERT INTO contract_type_definitions (code, name, description, active) VALUES
('PERIOD', 'Standard Time & Materials', 'Standard time and materials contract with simple pricing', true),
('SKI0217_2021', 'SKI Framework Agreement 2021', 'Danish public sector framework with step discount, 2% admin fee, and fixed invoice fee', true),
('SKI0217_2025', 'SKI Framework Agreement 2025', 'Updated Danish public sector framework with step discount and 4% admin fee', true),
('SKI0215_2025', 'SKI Simplified Agreement 2025', 'Simplified Danish public sector agreement with 4% admin fee only', true),
('SKI0217_2025_V2', 'SKI Framework Agreement 2025 V2', 'Alternative SKI framework with standard pricing', true);

-- Insert pricing rules for SKI0217_2021
-- Priority 10: Step discount (trapperabat)
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2021', 'ski21721-key', 'SKI trapperabat', 'PERCENT_DISCOUNT_ON_SUM', 'SUM_BEFORE_DISCOUNTS',
 NULL, NULL, 'trapperabat', NULL, NULL, 10, true);

-- Priority 20: 2% admin fee
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2021', 'ski21721-admin', '2% SKI administrationsgebyr', 'ADMIN_FEE_PERCENT', 'CURRENT_SUM',
 2.0, NULL, NULL, NULL, NULL, 20, true);

-- Priority 30: Fixed invoice fee 2000 DKK
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2021', 'ski21721-fee', 'Faktureringsgebyr', 'FIXED_DEDUCTION', 'CURRENT_SUM',
 NULL, 2000.00, NULL, NULL, NULL, 30, true);

-- Priority 40: General discount
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2021', 'ski21721-general', 'Generel rabat', 'GENERAL_DISCOUNT_PERCENT', 'CURRENT_SUM',
 NULL, NULL, NULL, NULL, NULL, 40, true);

-- Insert pricing rules for SKI0217_2025
-- Priority 10: Step discount (trapperabat)
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2025', 'ski21725-key', 'SKI trapperabat', 'PERCENT_DISCOUNT_ON_SUM', 'SUM_BEFORE_DISCOUNTS',
 NULL, NULL, 'trapperabat', NULL, NULL, 10, true);

-- Priority 20: 4% admin fee
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2025', 'ski21725-admin', '4% SKI administrationsgebyr', 'ADMIN_FEE_PERCENT', 'CURRENT_SUM',
 4.0, NULL, NULL, NULL, NULL, 20, true);

-- Priority 40: General discount
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0217_2025', 'ski21725-general', 'Generel rabat', 'GENERAL_DISCOUNT_PERCENT', 'CURRENT_SUM',
 NULL, NULL, NULL, NULL, NULL, 40, true);

-- Insert pricing rules for SKI0215_2025
-- Priority 20: 4% admin fee
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0215_2025', 'ski21525-admin', '4% SKI administrationsgebyr', 'ADMIN_FEE_PERCENT', 'CURRENT_SUM',
 4.0, NULL, NULL, NULL, NULL, 20, true);

-- Priority 40: General discount
INSERT INTO pricing_rule_steps (
    contract_type_code, rule_id, label, rule_step_type, step_base,
    percent, amount, param_key, valid_from, valid_to, priority, active
) VALUES
('SKI0215_2025', 'ski21525-general', 'Generel rabat', 'GENERAL_DISCOUNT_PERCENT', 'CURRENT_SUM',
 NULL, NULL, NULL, NULL, NULL, 40, true);

-- Note: PERIOD and SKI0217_2025_V2 only use fallback general discount rule
-- These are handled by PricingRuleCatalog's fallback mechanism
