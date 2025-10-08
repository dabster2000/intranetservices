-- Add audit tracking fields to projectdescriptions table
-- Tracks creation/modification timestamps and user identifiers (typically UUIDs from X-Requested-By header)

ALTER TABLE projectdescriptions
    ADD COLUMN created_at DATETIME(6) NULL COMMENT 'Creation timestamp',
    ADD COLUMN updated_at DATETIME(6) NULL COMMENT 'Last update timestamp',
    ADD COLUMN created_by VARCHAR(255) NULL COMMENT 'User identifier (usually UUID) who created the record',
    ADD COLUMN modified_by VARCHAR(255) NULL COMMENT 'User identifier (usually UUID) who last modified the record';

-- Populate existing records with default values
UPDATE projectdescriptions
SET created_at = NOW(),
    updated_at = NOW(),
    created_by = 'system',
    modified_by = 'system'
WHERE created_at IS NULL;

-- Make required fields non-nullable after population
ALTER TABLE projectdescriptions
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN created_by VARCHAR(255) NOT NULL;

-- Note: modified_by remains nullable as not all records have been modified yet
