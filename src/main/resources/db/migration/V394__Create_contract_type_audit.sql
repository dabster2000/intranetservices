-- ======================================================================================
-- V394: Create contract_type_audit table
-- ======================================================================================
-- Purpose: Real audit trail for framework agreement (contract type) mutations and their
--          rules (pricing rule steps, validation rules), with user attribution.
-- Spec:    docs/superpowers/specs/2026-07-10-framework-agreements-redesign-design.md §9.9
--
-- DESIGN DECISION: new table instead of reusing contract_rule_audit (V106)
-- --------------------------------------------------------------------------------------
-- contract_rule_audit is keyed by contract_uuid (NOT NULL) and rule_type
-- ENUM('VALIDATION','RATE_ADJUSTMENT','PRICING') / action ENUM(...,'DISABLE','ENABLE');
-- it belongs to the per-contract OVERRIDE system. Agreement-level entities
-- (contract_type_definitions, pricing_rule_steps, contract_validation_rules) are keyed
-- by contract_type_code, need an AGREEMENT entity type and a RESTORE operation, and have
-- no contract uuid. Bending the old table would require widening its NOT NULL key and
-- its ENUMs, breaking the override-audit contract. (The ContractRuleAudit JPA mapping
-- has also drifted from the V106 column names; it is left untouched.)
--
-- Rows are written by the application layer (JPA entity listener
-- dk.trustworks.intranet.contracts.audit.ContractTypeAuditListener) in the same
-- transaction as the mutation — no DB triggers (V108 decision: triggers need SUPER
-- privilege with binary logging enabled).
-- ======================================================================================

CREATE TABLE contract_type_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_type_code VARCHAR(50) NOT NULL COMMENT 'contract_type_definitions.code the change belongs to',
    entity_type VARCHAR(32) NOT NULL COMMENT 'AGREEMENT | PRICING_RULE | VALIDATION_RULE',
    rule_id VARCHAR(64) NULL COMMENT 'Rule id for rule-level entries; NULL for AGREEMENT entries',
    operation VARCHAR(16) NOT NULL COMMENT 'CREATE | UPDATE | DELETE (soft-disable or hard delete) | RESTORE',
    changed_by VARCHAR(100) NULL COMMENT 'User uuid from X-Requested-By (may be system:<client>); NULL when unattributed',
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'When the change happened',
    summary VARCHAR(1000) NULL COMMENT 'Short human-readable field diff, e.g. "active: true -> false"',

    -- Read path is WHERE contract_type_code = ? ORDER BY id DESC LIMIT n (id is monotonic,
    -- so it doubles as the newest-first sort key and avoids same-second timestamp ties).
    INDEX idx_contract_type_audit_code_id (contract_type_code, id),
    INDEX idx_contract_type_audit_changed_by (changed_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Audit trail for framework agreement (contract type) and rule mutations';
