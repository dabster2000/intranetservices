-- Expense AI question tree v1. Account numbers are implementation metadata;
-- employee-facing clients consume labels/results and keep account details hidden.

CREATE TABLE expense_classification_tree (
  tree_version VARCHAR(32) NOT NULL PRIMARY KEY,
  active       BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at   DATETIME(3) NOT NULL,
  created_by   VARCHAR(36) NOT NULL
);

CREATE TABLE expense_classification_node (
  uuid                 VARCHAR(36)  NOT NULL PRIMARY KEY,
  tree_version         VARCHAR(32)  NOT NULL,
  node_key             VARCHAR(64)  NOT NULL,
  prompt               VARCHAR(255) NOT NULL,
  answer_source_policy VARCHAR(40)  NOT NULL,
  required             BOOLEAN      NOT NULL DEFAULT TRUE,
  sort_order           INT          NOT NULL,
  visible_when_json    JSON         NULL,
  UNIQUE KEY uq_ecn_tree_node (tree_version, node_key),
  INDEX idx_ecn_tree_order (tree_version, sort_order),
  CONSTRAINT fk_ecn_tree FOREIGN KEY (tree_version) REFERENCES expense_classification_tree(tree_version)
);

CREATE TABLE expense_classification_option (
  uuid          VARCHAR(36)  NOT NULL PRIMARY KEY,
  tree_version  VARCHAR(32)  NOT NULL,
  node_key      VARCHAR(64)  NOT NULL,
  answer_key    VARCHAR(64)  NOT NULL,
  label         VARCHAR(160) NOT NULL,
  sort_order    INT          NOT NULL,
  UNIQUE KEY uq_eco_tree_node_answer (tree_version, node_key, answer_key),
  INDEX idx_eco_tree_node_order (tree_version, node_key, sort_order),
  CONSTRAINT fk_eco_tree FOREIGN KEY (tree_version) REFERENCES expense_classification_tree(tree_version)
);

CREATE TABLE expense_classification_result (
  uuid                    VARCHAR(36)  NOT NULL PRIMARY KEY,
  tree_version            VARCHAR(32)  NOT NULL,
  result_key              VARCHAR(80)  NOT NULL,
  employee_label          VARCHAR(180) NOT NULL,
  employee_summary        VARCHAR(255) NOT NULL,
  account_key             VARCHAR(80)  NOT NULL,
  tax_treatment           VARCHAR(32)  NULL,
  requires_finance_review BOOLEAN      NOT NULL DEFAULT FALSE,
  conditions_json         JSON         NOT NULL,
  sort_order              INT          NOT NULL,
  UNIQUE KEY uq_ecr_tree_result (tree_version, result_key),
  INDEX idx_ecr_tree_order (tree_version, sort_order),
  CONSTRAINT fk_ecr_tree FOREIGN KEY (tree_version) REFERENCES expense_classification_tree(tree_version)
);

CREATE TABLE expense_account_mapping (
  uuid           VARCHAR(36)  NOT NULL PRIMARY KEY,
  account_key    VARCHAR(80)  NOT NULL,
  companyuuid    VARCHAR(36)  NULL,
  account_number VARCHAR(16)  NOT NULL,
  account_name   VARCHAR(160) NOT NULL,
  active         BOOLEAN      NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_eam_key_company (account_key, companyuuid),
  INDEX idx_eam_key_active (account_key, active)
);

CREATE TABLE expense_receipt_analysis (
  analysis_id            VARCHAR(36) NOT NULL PRIMARY KEY,
  useruuid               VARCHAR(36) NOT NULL,
  tree_version           VARCHAR(32) NOT NULL,
  created_at             DATETIME(3) NOT NULL,
  receipt_facts_json     JSON        NOT NULL,
  proposed_answers_json  JSON        NOT NULL,
  warnings_json          JSON        NOT NULL,
  raw_model_summary      TEXT        NULL,
  INDEX idx_era_user_created (useruuid, created_at),
  CONSTRAINT fk_era_tree FOREIGN KEY (tree_version) REFERENCES expense_classification_tree(tree_version)
);

CREATE TABLE expense_classification (
  uuid                    VARCHAR(36) NOT NULL PRIMARY KEY,
  expense_uuid            VARCHAR(36) NOT NULL,
  useruuid                VARCHAR(36) NOT NULL,
  analysis_id             VARCHAR(36) NULL,
  tree_version            VARCHAR(32) NOT NULL,
  ai_used                 BOOLEAN     NOT NULL DEFAULT FALSE,
  ai_ignored              BOOLEAN     NOT NULL DEFAULT FALSE,
  decision_result_key     VARCHAR(80) NOT NULL,
  account_key             VARCHAR(80) NOT NULL,
  account_number          VARCHAR(16) NOT NULL,
  account_name            VARCHAR(160) NOT NULL,
  requires_finance_review BOOLEAN     NOT NULL DEFAULT FALSE,
  answers_json            JSON        NOT NULL,
  ignored_ai_answers_json JSON        NOT NULL,
  created_at              DATETIME(3) NOT NULL,
  UNIQUE KEY uq_ec_expense (expense_uuid),
  INDEX idx_ec_user_created (useruuid, created_at),
  CONSTRAINT fk_ec_expense FOREIGN KEY (expense_uuid) REFERENCES expenses(uuid) ON DELETE CASCADE,
  CONSTRAINT fk_ec_tree FOREIGN KEY (tree_version) REFERENCES expense_classification_tree(tree_version)
);

INSERT INTO expense_classification_tree (tree_version, active, created_at, created_by)
VALUES ('2026-05-19.v1', TRUE, NOW(3), 'SYSTEM_SEED');

INSERT INTO expense_classification_node (uuid, tree_version, node_key, prompt, answer_source_policy, required, sort_order, visible_when_json) VALUES
  (UUID(), '2026-05-19.v1', 'root', 'What kind of expense is this?', 'AI_ALLOWED_USER_CAN_OVERRIDE', TRUE, 10, NULL),
  (UUID(), '2026-05-19.v1', 'food_situation', 'What was the food or catering situation?', 'USER_REQUIRED', TRUE, 20, '{"root":"food_catering"}'),
  (UUID(), '2026-05-19.v1', 'meal_purpose', 'Was it customer care or a normal lunch break?', 'USER_REQUIRED', TRUE, 30, '{"food_situation":"with_customer"}'),
  (UUID(), '2026-05-19.v1', 'tw_event_type', 'Was it a professional or social arrangement?', 'USER_REQUIRED', TRUE, 40, '{"food_situation":"trustworks_event"}'),
  (UUID(), '2026-05-19.v1', 'tw_event_location', 'Where did it take place?', 'USER_REQUIRED', TRUE, 50, '{"food_situation":"trustworks_event"}'),
  (UUID(), '2026-05-19.v1', 'overtime_location', 'Where was the overtime meal bought or eaten?', 'USER_REQUIRED', TRUE, 60, '{"food_situation":"overtime_meal"}'),
  (UUID(), '2026-05-19.v1', 'travel_cost', 'What travel cost is it?', 'USER_REQUIRED', TRUE, 70, '{"root":"transport_travel"}'),
  (UUID(), '2026-05-19.v1', 'hotel_country', 'Was the hotel stay in Denmark or abroad?', 'AI_ALLOWED_USER_CAN_OVERRIDE', TRUE, 80, '{"travel_cost":"hotel"}'),
  (UUID(), '2026-05-19.v1', 'bridge_kind', 'Which bridge or toll was it?', 'AI_ALLOWED_USER_CAN_OVERRIDE', TRUE, 90, '{"travel_cost":"bridge_toll"}'),
  (UUID(), '2026-05-19.v1', 'gift_recipient', 'Who was the gift for?', 'USER_REQUIRED', TRUE, 100, '{"root":"gift"}'),
  (UUID(), '2026-05-19.v1', 'software_supplier_country', 'Where is the software supplier invoicing from?', 'AI_ALLOWED_USER_CAN_OVERRIDE', TRUE, 110, '{"root":"software"}'),
  (UUID(), '2026-05-19.v1', 'hardware_under_threshold', 'Is the hardware under 36,000 kr?', 'AI_ALLOWED_USER_CAN_OVERRIDE', TRUE, 120, '{"root":"hardware"}'),
  (UUID(), '2026-05-19.v1', 'hardware_private_use', 'Can it also be used privately?', 'USER_REQUIRED', TRUE, 130, '{"hardware_under_threshold":"yes"}'),
  (UUID(), '2026-05-19.v1', 'course_supplier_country', 'Where is the course supplier invoicing from?', 'AI_ALLOWED_USER_CAN_OVERRIDE', TRUE, 140, '{"root":"course_training"}');

INSERT INTO expense_classification_option (uuid, tree_version, node_key, answer_key, label, sort_order) VALUES
  (UUID(), '2026-05-19.v1', 'root', 'food_catering', 'Food / catering', 10),
  (UUID(), '2026-05-19.v1', 'root', 'transport_travel', 'Transport / travel', 20),
  (UUID(), '2026-05-19.v1', 'root', 'gift', 'Gift', 30),
  (UUID(), '2026-05-19.v1', 'root', 'software', 'Software', 40),
  (UUID(), '2026-05-19.v1', 'root', 'hardware', 'Hardware', 50),
  (UUID(), '2026-05-19.v1', 'root', 'office_supplies', 'Office supplies', 60),
  (UUID(), '2026-05-19.v1', 'root', 'internet', 'Internet', 70),
  (UUID(), '2026-05-19.v1', 'root', 'course_training', 'Course / training', 80),
  (UUID(), '2026-05-19.v1', 'root', 'other_unsure', 'Other / not sure', 90),
  (UUID(), '2026-05-19.v1', 'food_situation', 'no_customer', 'No customer purpose', 10),
  (UUID(), '2026-05-19.v1', 'food_situation', 'customer_site_lunch', 'Lunch while working at customer', 20),
  (UUID(), '2026-05-19.v1', 'food_situation', 'with_customer', 'With customer', 30),
  (UUID(), '2026-05-19.v1', 'food_situation', 'trustworks_event', 'Trustworks event', 40),
  (UUID(), '2026-05-19.v1', 'food_situation', 'overtime_meal', 'Overtime meal', 50),
  (UUID(), '2026-05-19.v1', 'meal_purpose', 'customer_care', 'Customer care', 10),
  (UUID(), '2026-05-19.v1', 'meal_purpose', 'normal_lunch_break', 'Normal lunch break', 20),
  (UUID(), '2026-05-19.v1', 'tw_event_type', 'professional', 'Professional arrangement', 10),
  (UUID(), '2026-05-19.v1', 'tw_event_type', 'social', 'Social arrangement', 20),
  (UUID(), '2026-05-19.v1', 'tw_event_location', 'trustworks_premises', 'Trustworks premises', 10),
  (UUID(), '2026-05-19.v1', 'tw_event_location', 'external_location', 'External restaurant or location', 20),
  (UUID(), '2026-05-19.v1', 'overtime_location', 'office', 'At the office', 10),
  (UUID(), '2026-05-19.v1', 'overtime_location', 'city', 'Out in the city', 20),
  (UUID(), '2026-05-19.v1', 'travel_cost', 'general_transport', 'Taxi, train, bus, flight or ferry', 10),
  (UUID(), '2026-05-19.v1', 'travel_cost', 'hotel', 'Hotel', 20),
  (UUID(), '2026-05-19.v1', 'travel_cost', 'bridge_toll', 'Bridge toll', 30),
  (UUID(), '2026-05-19.v1', 'travel_cost', 'parking', 'Parking', 40),
  (UUID(), '2026-05-19.v1', 'travel_cost', 'car_rental_short', 'Car rental under 6 months', 50),
  (UUID(), '2026-05-19.v1', 'hotel_country', 'dk', 'Denmark', 10),
  (UUID(), '2026-05-19.v1', 'hotel_country', 'abroad', 'Abroad', 20),
  (UUID(), '2026-05-19.v1', 'bridge_kind', 'sweden_oresund', 'Sweden / Oresund', 10),
  (UUID(), '2026-05-19.v1', 'bridge_kind', 'dk_storebaelt', 'Denmark / Storebaelt', 20),
  (UUID(), '2026-05-19.v1', 'gift_recipient', 'client', 'Client', 10),
  (UUID(), '2026-05-19.v1', 'gift_recipient', 'colleague', 'Colleague', 20),
  (UUID(), '2026-05-19.v1', 'software_supplier_country', 'dk', 'Denmark', 10),
  (UUID(), '2026-05-19.v1', 'software_supplier_country', 'eu', 'EU', 20),
  (UUID(), '2026-05-19.v1', 'software_supplier_country', 'outside_eu', 'Outside EU', 30),
  (UUID(), '2026-05-19.v1', 'hardware_under_threshold', 'yes', 'Yes', 10),
  (UUID(), '2026-05-19.v1', 'hardware_under_threshold', 'no', 'No / not sure', 20),
  (UUID(), '2026-05-19.v1', 'hardware_private_use', 'possible', 'Yes, private use is possible', 10),
  (UUID(), '2026-05-19.v1', 'hardware_private_use', 'work_only', 'No, only work use', 20),
  (UUID(), '2026-05-19.v1', 'course_supplier_country', 'dk', 'Denmark', 10),
  (UUID(), '2026-05-19.v1', 'course_supplier_country', 'eu', 'EU', 20),
  (UUID(), '2026-05-19.v1', 'course_supplier_country', 'outside_eu', 'Outside EU', 30);

INSERT INTO expense_account_mapping (uuid, account_key, companyuuid, account_number, account_name, active) VALUES
  (UUID(), 'internal_food', NULL, '3585', 'Internal food', TRUE),
  (UUID(), 'customer_meal_care', NULL, '4006', 'Customer care meal', TRUE),
  (UUID(), 'tw_professional_event_internal', NULL, '4009', 'Professional event internal', TRUE),
  (UUID(), 'tw_social_event', NULL, '3589', 'Social event', TRUE),
  (UUID(), 'overtime_meal_external', NULL, '3591', 'Overtime meal external', TRUE),
  (UUID(), 'general_travel_transport', NULL, '4050', 'Travel transport', TRUE),
  (UUID(), 'hotel_dk', NULL, '4031', 'Hotel Denmark', TRUE),
  (UUID(), 'hotel_abroad', NULL, '4030', 'Hotel abroad', TRUE),
  (UUID(), 'bridge_sweden_oresund', NULL, '4061', 'Oresund bridge', TRUE),
  (UUID(), 'bridge_dk_storebaelt', NULL, '4066', 'Storebaelt bridge', TRUE),
  (UUID(), 'parking', NULL, '4055', 'Parking', TRUE),
  (UUID(), 'client_gift', NULL, '4008', 'Client gift', TRUE),
  (UUID(), 'colleague_gift', NULL, '3585', 'Colleague gift', TRUE),
  (UUID(), 'software_dk', NULL, '5214', 'Software Denmark', TRUE),
  (UUID(), 'software_eu', NULL, '5216', 'Software EU', TRUE),
  (UUID(), 'software_outside_eu', NULL, '5217', 'Software outside EU', TRUE),
  (UUID(), 'hardware_private_use', NULL, '5219', 'Hardware private use', TRUE),
  (UUID(), 'hardware_work_only', NULL, '5218', 'Hardware work only', TRUE),
  (UUID(), 'office_supplies', NULL, '5233', 'Office supplies', TRUE),
  (UUID(), 'internet', NULL, '5242', 'Internet', TRUE),
  (UUID(), 'course_dk', NULL, '3560', 'Course Denmark', TRUE),
  (UUID(), 'course_eu', NULL, '3561', 'Course EU', TRUE),
  (UUID(), 'course_outside_eu', NULL, '3562', 'Course outside EU', TRUE),
  (UUID(), 'finance_review_fallback', NULL, '9998', 'Finance review', TRUE);

INSERT INTO expense_classification_result (uuid, tree_version, result_key, employee_label, employee_summary, account_key, tax_treatment, requires_finance_review, conditions_json, sort_order) VALUES
  (UUID(), '2026-05-19.v1', 'INTERNAL_FOOD_NO_CUSTOMER', 'Food without customer purpose', 'This will be handled as internal food.', 'internal_food', 'U', FALSE, '{"root":"food_catering","food_situation":"no_customer"}', 10),
  (UUID(), '2026-05-19.v1', 'CUSTOMER_SITE_LUNCH', 'Lunch while working at customer', 'This will be handled as customer-site lunch.', 'internal_food', 'U', FALSE, '{"root":"food_catering","food_situation":"customer_site_lunch"}', 20),
  (UUID(), '2026-05-19.v1', 'CUSTOMER_MEAL_CARE', 'Customer care meal', 'This will be handled as customer care.', 'customer_meal_care', '25M', FALSE, '{"root":"food_catering","food_situation":"with_customer","meal_purpose":"customer_care"}', 30),
  (UUID(), '2026-05-19.v1', 'NORMAL_CUSTOMER_LUNCH_BREAK', 'Normal lunch break at customer', 'This will be handled as an ordinary lunch break.', 'internal_food', 'U', FALSE, '{"root":"food_catering","food_situation":"with_customer","meal_purpose":"normal_lunch_break"}', 40),
  (UUID(), '2026-05-19.v1', 'TW_PROFESSIONAL_EVENT_INTERNAL', 'Professional Trustworks event at Trustworks', 'This will be handled as a professional internal event.', 'tw_professional_event_internal', 'FM', FALSE, '{"root":"food_catering","food_situation":"trustworks_event","tw_event_type":"professional","tw_event_location":"trustworks_premises"}', 50),
  (UUID(), '2026-05-19.v1', 'TW_SOCIAL_EVENT_INTERNAL', 'Social Trustworks event at Trustworks', 'This will be handled as a social internal event.', 'tw_social_event', 'U', FALSE, '{"root":"food_catering","food_situation":"trustworks_event","tw_event_type":"social","tw_event_location":"trustworks_premises"}', 60),
  (UUID(), '2026-05-19.v1', 'TW_PROFESSIONAL_EVENT_EXTERNAL', 'Professional Trustworks event at external location', 'Finance will review this route before upload.', 'finance_review_fallback', 'TBD', TRUE, '{"root":"food_catering","food_situation":"trustworks_event","tw_event_type":"professional","tw_event_location":"external_location"}', 70),
  (UUID(), '2026-05-19.v1', 'TW_SOCIAL_EVENT_EXTERNAL', 'Social Trustworks event at external location', 'This will be handled as a social event.', 'tw_social_event', 'U', FALSE, '{"root":"food_catering","food_situation":"trustworks_event","tw_event_type":"social","tw_event_location":"external_location"}', 80),
  (UUID(), '2026-05-19.v1', 'OVERTIME_MEAL_OFFICE', 'Overtime meal at the office', 'Finance will review this route before upload.', 'finance_review_fallback', 'FM', TRUE, '{"root":"food_catering","food_situation":"overtime_meal","overtime_location":"office"}', 90),
  (UUID(), '2026-05-19.v1', 'OVERTIME_MEAL_EXTERNAL', 'Overtime meal out in the city', 'This will be handled as an external overtime meal.', 'overtime_meal_external', '25% moms', FALSE, '{"root":"food_catering","food_situation":"overtime_meal","overtime_location":"city"}', 100),
  (UUID(), '2026-05-19.v1', 'GENERAL_TRAVEL_TRANSPORT', 'Travel transport', 'This will be handled as travel transport.', 'general_travel_transport', 'U', FALSE, '{"root":"transport_travel","travel_cost":"general_transport"}', 110),
  (UUID(), '2026-05-19.v1', 'HOTEL_DK', 'Hotel stay in Denmark', 'This will be handled as hotel in Denmark.', 'hotel_dk', 'FM', FALSE, '{"root":"transport_travel","travel_cost":"hotel","hotel_country":"dk"}', 120),
  (UUID(), '2026-05-19.v1', 'HOTEL_ABROAD', 'Hotel stay abroad', 'This will be handled as hotel abroad.', 'hotel_abroad', 'U', FALSE, '{"root":"transport_travel","travel_cost":"hotel","hotel_country":"abroad"}', 130),
  (UUID(), '2026-05-19.v1', 'BRIDGE_SWEDEN_ORESUND', 'Oresund bridge toll', 'This will be handled as an Oresund bridge toll.', 'bridge_sweden_oresund', 'FM', FALSE, '{"root":"transport_travel","travel_cost":"bridge_toll","bridge_kind":"sweden_oresund"}', 140),
  (UUID(), '2026-05-19.v1', 'BRIDGE_DK_STOREBAELT', 'Storebaelt bridge toll', 'This will be handled as a Storebaelt bridge toll.', 'bridge_dk_storebaelt', 'U', FALSE, '{"root":"transport_travel","travel_cost":"bridge_toll","bridge_kind":"dk_storebaelt"}', 150),
  (UUID(), '2026-05-19.v1', 'PARKING', 'Parking', 'This will be handled as parking.', 'parking', NULL, FALSE, '{"root":"transport_travel","travel_cost":"parking"}', 160),
  (UUID(), '2026-05-19.v1', 'CAR_RENTAL_SHORT_TERM', 'Car rental under 6 months', 'This will be handled as travel transport.', 'general_travel_transport', 'U', FALSE, '{"root":"transport_travel","travel_cost":"car_rental_short"}', 170),
  (UUID(), '2026-05-19.v1', 'CLIENT_GIFT', 'Gift for a client', 'Your answers are saved for Finance. The Finance route is selected in the background.', 'client_gift', 'U', FALSE, '{"root":"gift","gift_recipient":"client"}', 180),
  (UUID(), '2026-05-19.v1', 'COLLEAGUE_GIFT', 'Gift for a colleague', 'This will be handled as a colleague gift.', 'colleague_gift', 'U', FALSE, '{"root":"gift","gift_recipient":"colleague"}', 190),
  (UUID(), '2026-05-19.v1', 'SOFTWARE_DK', 'Software from Denmark', 'This will be handled as Danish software.', 'software_dk', 'FM', FALSE, '{"root":"software","software_supplier_country":"dk"}', 200),
  (UUID(), '2026-05-19.v1', 'SOFTWARE_EU', 'Software from the EU', 'This will be handled as EU software.', 'software_eu', 'U(EU)', FALSE, '{"root":"software","software_supplier_country":"eu"}', 210),
  (UUID(), '2026-05-19.v1', 'SOFTWARE_OUTSIDE_EU', 'Software from outside the EU', 'This will be handled as software from outside the EU.', 'software_outside_eu', 'U(EU)', FALSE, '{"root":"software","software_supplier_country":"outside_eu"}', 220),
  (UUID(), '2026-05-19.v1', 'HARDWARE_PRIVATE_USE', 'Hardware that can also be used privately', 'This will be handled as hardware with possible private use.', 'hardware_private_use', 'HM', FALSE, '{"root":"hardware","hardware_under_threshold":"yes","hardware_private_use":"possible"}', 230),
  (UUID(), '2026-05-19.v1', 'HARDWARE_WORK_ONLY', 'Hardware for work only', 'This will be handled as work-only hardware.', 'hardware_work_only', 'FM', FALSE, '{"root":"hardware","hardware_under_threshold":"yes","hardware_private_use":"work_only"}', 240),
  (UUID(), '2026-05-19.v1', 'HARDWARE_FINANCE_REVIEW', 'Hardware requiring Finance review', 'Finance will review this route before upload.', 'finance_review_fallback', 'TBD', TRUE, '{"root":"hardware","hardware_under_threshold":"no"}', 250),
  (UUID(), '2026-05-19.v1', 'OFFICE_SUPPLIES', 'Office supplies', 'This will be handled as office supplies.', 'office_supplies', 'FM', FALSE, '{"root":"office_supplies"}', 260),
  (UUID(), '2026-05-19.v1', 'INTERNET', 'Internet', 'This will be handled as internet.', 'internet', 'HM', FALSE, '{"root":"internet"}', 270),
  (UUID(), '2026-05-19.v1', 'COURSE_DK', 'Course from Denmark', 'This will be handled as a Danish course supplier.', 'course_dk', 'FM', FALSE, '{"root":"course_training","course_supplier_country":"dk"}', 280),
  (UUID(), '2026-05-19.v1', 'COURSE_EU', 'Course from the EU', 'This will be handled as an EU course supplier.', 'course_eu', 'U(EU)', FALSE, '{"root":"course_training","course_supplier_country":"eu"}', 290),
  (UUID(), '2026-05-19.v1', 'COURSE_OUTSIDE_EU', 'Course from outside the EU', 'This will be handled as a course supplier outside the EU.', 'course_outside_eu', 'U(EU)', FALSE, '{"root":"course_training","course_supplier_country":"outside_eu"}', 300),
  (UUID(), '2026-05-19.v1', 'OTHER_OR_UNCLEAR', 'Other / not sure', 'Finance will review this route before upload.', 'finance_review_fallback', NULL, TRUE, '{"root":"other_unsure"}', 310);
