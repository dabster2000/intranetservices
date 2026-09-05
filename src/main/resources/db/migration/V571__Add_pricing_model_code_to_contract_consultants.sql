-- JK Team 2.0 / WP5 — pricing model on the consultant line (D11).
--
-- Nullable, additive, no default: existing lines simply have no model. Kept as its own
-- migration because contract_consultants is a hot table — one small online ADD COLUMN
-- (MariaDB performs it INSTANT for a trailing nullable column), no constraint, no index.
-- Values reference pricing_model_definitions.code (V570); the reference is enforced by
-- ContractValidationService rather than an FK so a model can be retired without blocking
-- the lines that carried it.
ALTER TABLE contract_consultants
    ADD COLUMN pricing_model_code VARCHAR(32) NULL
        COMMENT 'pricing_model_definitions.code — the commercial model for this line (JK Team 2.0 WP5)';
