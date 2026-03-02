-- =============================================================================
-- Migration V219: Create client activity log table
--
-- Purpose:
--   Creates a change history table for tracking field-level modifications
--   to client-related entities (clients, contracts, clientdata, consultants,
--   projects). This enables an activity feed in the client detail UI.
--
-- Design decisions:
--   - No foreign key constraints (loosely coupled for flexibility)
--   - Application-level insert only (follows V108 philosophy)
--   - Microsecond precision timestamps for accurate ordering
--   - TEXT columns for old/new values to handle varying field sizes
--
-- Rollback:
--   DROP TABLE IF EXISTS client_activity_log;
-- =============================================================================

CREATE TABLE client_activity_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_uuid VARCHAR(36)  NOT NULL  COMMENT 'Client UUID this activity belongs to',
    entity_type VARCHAR(50)  NOT NULL  COMMENT 'Entity type: CLIENT, CONTRACT, CLIENTDATA, CONTRACT_CONSULTANT, CONTRACT_PROJECT',
    entity_uuid VARCHAR(36)  NOT NULL  COMMENT 'UUID of the entity that was changed',
    entity_name VARCHAR(255)           COMMENT 'Human-readable entity name (e.g. contract name)',
    action      VARCHAR(20)  NOT NULL  COMMENT 'Change action: CREATED, MODIFIED, DELETED',
    field_name  VARCHAR(100)           COMMENT 'Field that was changed (NULL for CREATED/DELETED)',
    old_value   TEXT                   COMMENT 'Previous field value (NULL for CREATED)',
    new_value   TEXT                   COMMENT 'New field value (NULL for DELETED)',
    modified_by VARCHAR(36)  NOT NULL  COMMENT 'User UUID who made the change',
    modified_at DATETIME(6)  NOT NULL  DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Timestamp of the change',

    INDEX idx_cal_client     (client_uuid),
    INDEX idx_cal_modified_at (modified_at),
    INDEX idx_cal_entity     (entity_type, entity_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Activity log tracking field-level changes to client-related entities';
