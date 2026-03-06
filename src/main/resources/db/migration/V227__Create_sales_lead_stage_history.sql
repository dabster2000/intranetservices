-- =============================================================================
-- Migration V227: Create sales_lead_stage_history table
--
-- Purpose:
--   Track all pipeline stage transitions for sales leads. Each row records a
--   transition from one stage to another, enabling:
--   - Stage velocity analysis (time spent in each stage)
--   - Pipeline flow metrics (conversion rates between stages)
--   - Audit trail for lead progression
--
-- Grain: One row per stage transition event
-- Source: Populated by application code (SalesService) on lead status changes
--
-- Columns:
--   id          - Auto-increment surrogate key
--   lead_uuid   - FK to sales_lead.uuid
--   from_stage  - Previous stage (NULL for initial creation)
--   to_stage    - New stage after transition
--   changed_by  - UUID of user who made the change (NULL if system)
--   changed_at  - Timestamp of the transition (millisecond precision)
--
-- Indexes:
--   idx_slsh_lead_uuid    - Fast lookup by lead
--   idx_slsh_changed_at   - Time-range queries for reporting
--   idx_slsh_lead_stage   - Composite for stage-specific lead queries
--
-- Rollback: DROP TABLE IF EXISTS sales_lead_stage_history;
-- =============================================================================

CREATE TABLE IF NOT EXISTS sales_lead_stage_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_uuid VARCHAR(36) NOT NULL,
    from_stage VARCHAR(20) NULL,
    to_stage VARCHAR(20) NOT NULL,
    changed_by VARCHAR(36) NULL,
    changed_at DATETIME(3) DEFAULT NOW(3),
    INDEX idx_slsh_lead_uuid (lead_uuid),
    INDEX idx_slsh_changed_at (changed_at),
    INDEX idx_slsh_lead_stage (lead_uuid, to_stage),
    CONSTRAINT fk_slsh_lead FOREIGN KEY (lead_uuid) REFERENCES sales_lead(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
