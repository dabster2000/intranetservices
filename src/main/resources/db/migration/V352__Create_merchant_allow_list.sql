-- ====================================================================
-- V352: Create merchant_allow_list table.
--
-- Purpose: Supports the AI Validation Console "Add to allow-list" quick
-- action (Phase 3). Administrators can flag merchant name patterns that
-- should auto-approve expenses under a specific rule bucket without
-- triggering the normal threshold checks (e.g., trusted office canteen
-- vendors for the R_MEAL_COST_PER_PERSON rule).
--
-- Design decisions:
--   rule_id is a VARCHAR string, not a foreign key, because ai_rule_catalog
--   entries can be reconfigured or renamed without cascading deletions of
--   curated allow-list entries. Allow-list data should survive rule catalog
--   edits.
--
--   match_kind ENUM('EXACT', 'CONTAINS') controls how the evaluation engine
--   compares the pattern against extracted_merchant_name:
--     EXACT    — case-insensitive equality
--     CONTAINS — case-insensitive substring match (default)
--
--   added_by_uuid is nullable to accommodate system-seeded bootstrap rows
--   loaded during migration without a specific admin actor.
--
-- Columns:
--   uuid                  VARCHAR(36) PK — standard UUID primary key
--   rule_id               VARCHAR(80) — allow-list bucket identifier
--       (e.g. R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK). 80 chars is a safe
--       ceiling; longest current rule ID is 22 chars.
--   merchant_name_pattern VARCHAR(200) — pattern string to match against
--       the AI-extracted merchant name (expenses.extracted_merchant_name).
--   match_kind            ENUM('EXACT','CONTAINS') DEFAULT 'CONTAINS'
--       — matching strategy applied during rule evaluation.
--   notes                 TEXT NULL — optional admin note explaining why
--       this entry was added (audit trail / human context).
--   added_by_uuid         VARCHAR(36) NULL — UUID of the admin who added
--       this entry; NULL for system-seeded entries.
--   created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
--       — row insertion timestamp (UTC, MariaDB CURRENT_TIMESTAMP default).
--
-- Indexes:
--   idx_mal_rule    (rule_id)               — primary lookup path: "all
--       allow-list patterns for this rule bucket"
--   idx_mal_pattern (merchant_name_pattern) — supports deduplication
--       checks before inserting a new pattern
--
-- Backwards-compatibility: new table, no existing rows affected.
--
-- Rollback strategy: DROP TABLE merchant_allow_list;
--   Safe to run if this migration is the only change in the release. No
--   other tables reference this table.
--
-- Impact on Quarkus entities/repositories:
--   A new MerchantAllowList entity (Panache) and
--   MerchantAllowListRepository will be introduced in the expenseservice
--   package in the same release. No existing entities are altered.
--
-- MariaDB version: 10.x — ENUM, DEFAULT CURRENT_TIMESTAMP, and inline
--   INDEX syntax are all supported.
-- ====================================================================

CREATE TABLE merchant_allow_list (
  uuid                   VARCHAR(36)  NOT NULL
    COMMENT 'UUID primary key for this allow-list entry',
  rule_id                VARCHAR(80)  NOT NULL
    COMMENT 'Allow-list bucket id, e.g. R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK',
  merchant_name_pattern  VARCHAR(200) NOT NULL
    COMMENT 'Case-insensitive substring or exact match string',
  match_kind             ENUM('EXACT', 'CONTAINS') NOT NULL DEFAULT 'CONTAINS'
    COMMENT 'How to compare the pattern against the extracted merchant name',
  notes                  TEXT NULL
    COMMENT 'Optional admin note explaining why this entry was added',
  added_by_uuid          VARCHAR(36) NULL
    COMMENT 'UUID of the admin who added this entry; NULL for system-seeded entries',
  created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    COMMENT 'Row insertion timestamp (UTC)',
  PRIMARY KEY (uuid),
  INDEX idx_mal_rule (rule_id),
  INDEX idx_mal_pattern (merchant_name_pattern)
);
