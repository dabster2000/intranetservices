-- Create table for grouping bonus eligibility
CREATE TABLE invoice_bonus_eligibility_group (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL
);

-- Add foreign key column to existing table (nullable to preserve existing behavior)
ALTER TABLE invoice_bonus_eligibility
    ADD COLUMN group_uuid VARCHAR(36) NULL;

-- Add index and FK constraint
CREATE INDEX idx_invoice_bonus_eligibility_group_uuid ON invoice_bonus_eligibility(group_uuid);


-- Create a default group and backfill existing rows
SET @default_uuid = '00000000-0000-0000-0000-000000000000';
INSERT INTO invoice_bonus_eligibility_group (uuid, name, period_start, period_end)
VALUES (@default_uuid, 'Default Bonus Eligibility Group', DATE('2000-01-01'), DATE('2999-12-31'));

UPDATE invoice_bonus_eligibility
SET group_uuid = @default_uuid
WHERE group_uuid IS NULL;
