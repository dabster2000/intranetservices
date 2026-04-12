-- V279__Create_questionnaire_tables.sql
-- Generic questionnaire system with KYC seed data

-- 1. Questionnaire definition
CREATE TABLE questionnaire (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    deadline DATE,
    status ENUM('ACTIVE', 'CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. Questions belonging to a questionnaire
CREATE TABLE questionnaire_question (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    questionnaire_uuid VARCHAR(36) NOT NULL,
    question_text TEXT NOT NULL,
    question_type ENUM('TEXT', 'CHECKBOX_TEXT') NOT NULL DEFAULT 'TEXT',
    sort_order INT NOT NULL DEFAULT 0,
    config_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qq_questionnaire (questionnaire_uuid),
    CONSTRAINT fk_qq_questionnaire
        FOREIGN KEY (questionnaire_uuid) REFERENCES questionnaire(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. One submission per consultant per client per questionnaire
CREATE TABLE questionnaire_submission (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    questionnaire_uuid VARCHAR(36) NOT NULL,
    client_uuid VARCHAR(36) NOT NULL,
    user_uuid VARCHAR(36) NOT NULL,
    submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qs_questionnaire (questionnaire_uuid),
    INDEX idx_qs_client (client_uuid),
    INDEX idx_qs_user (user_uuid),
    UNIQUE KEY uq_submission (questionnaire_uuid, client_uuid, user_uuid),
    CONSTRAINT fk_qs_questionnaire
        FOREIGN KEY (questionnaire_uuid) REFERENCES questionnaire(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Individual answers
CREATE TABLE questionnaire_answer (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    submission_uuid VARCHAR(36) NOT NULL,
    question_uuid VARCHAR(36) NOT NULL,
    answer_text TEXT,
    answer_json TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qa_submission (submission_uuid),
    INDEX idx_qa_question (question_uuid),
    CONSTRAINT fk_qa_submission
        FOREIGN KEY (submission_uuid) REFERENCES questionnaire_submission(uuid),
    CONSTRAINT fk_qa_question
        FOREIGN KEY (question_uuid) REFERENCES questionnaire_question(uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed: Know Your Client questionnaire
INSERT INTO questionnaire (uuid, title, description, deadline, status) VALUES (
    'kyc-2026-q2',
    'Know Your Client',
    'Strengthening client relationships, sharpening our consulting edge, and living our DNA & Code of Quality.',
    '2026-04-23',
    'ACTIVE'
);

INSERT INTO questionnaire_question (uuid, questionnaire_uuid, question_text, question_type, sort_order, config_json) VALUES
    ('kyc-q1', 'kyc-2026-q2', 'Hvad er kundens største udfordringer?', 'TEXT', 1, NULL),
    ('kyc-q2', 'kyc-2026-q2', 'Hvilke andre projekter kører hos kunden?', 'TEXT', 2, NULL),
    ('kyc-q3', 'kyc-2026-q2', 'Hvad er kundens vigtigste forretningsmæssige mål for 2026?', 'TEXT', 3, NULL),
    ('kyc-q4', 'kyc-2026-q2', 'Bruger kunden normalt vores 5 ydelsesområder – og hvordan?', 'CHECKBOX_TEXT', 4,
     '{"options":["Projektledelse","Business Architecture","Solution Architecture","Application Development","Integrations"]}');
