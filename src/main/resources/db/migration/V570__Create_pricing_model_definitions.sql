-- JK Team 2.0 / WP5 — pricing models as reference data (spec WP5, D11).
--
-- The four commercial models for junior consultants, seeded as rows rather than an enum in
-- code, following the contract_type_definitions precedent (V96). contract_type_definitions
-- itself is the wrong home: its pricing_rule_steps are invoice-level SKI discounts and admin
-- fees, not per-consultant rates. The model lives on the consultant line
-- (contract_consultants.pricing_model_code, V571), never on contracts.
CREATE TABLE IF NOT EXISTS pricing_model_definitions (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL COMMENT 'Stable identifier stored on contract_consultants.pricing_model_code',
    name        VARCHAR(255) NOT NULL COMMENT 'Display name',
    description TEXT         NULL     COMMENT 'The pricing principle in one sentence',
    active      BOOLEAN      NOT NULL DEFAULT TRUE COMMENT 'Soft delete — inactive models are hidden but preserved',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_pricing_model_definitions_code (code),
    KEY idx_pricing_model_definitions_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
COMMENT='Pricing models for consultant lines (JK Team 2.0 WP5)';

INSERT INTO pricing_model_definitions (code, name, description, active) VALUES
    ('FULL_FROM_START', 'Model 1 — Fuld pris fra start', 'Full rate (~600 kr/t) from day one.', TRUE),
    ('STEPPED',         'Model 2 — Trappemodel',         'Stepped rate agreed up front, e.g. 300 → 600 kr/t; each step is its own dated consultant line.', TRUE),
    ('PILOT_FREE',      'Model 3 — "Føl"-modellen',      '0 → 600 kr/t with a hard deadline; pairs with a declared zero-rate line (zero_rate_reason = PILOT_FREE).', TRUE),
    ('COLLEAGUE_HOURS', 'Model 4 — Hjælp kollega-timer', 'A junior on a colleague''s ordinary contract — a consultant line on that contract, no separate concept.', TRUE)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);
