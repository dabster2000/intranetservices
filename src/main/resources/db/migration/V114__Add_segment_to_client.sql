-- Add segment column to client table for industry categorization
-- ClientSegment enum values: PUBLIC, HEALTH, FINANCIAL, ENERGY, EDUCATION, OTHER
-- Default value: OTHER for all new and existing clients

ALTER TABLE client
    ADD COLUMN segment VARCHAR(20) NOT NULL DEFAULT 'OTHER'
    COMMENT 'Industry segment: PUBLIC, HEALTH, FINANCIAL, ENERGY, EDUCATION, OTHER';
