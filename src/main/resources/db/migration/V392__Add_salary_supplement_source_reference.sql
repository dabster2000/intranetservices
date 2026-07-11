-- Idempotency key for individual-bonus PREPAID advance supplements, mirroring
-- salary_lump_sum.source_reference (V192). Backs IndividualBonusSupplementWriter's find-or-update so a
-- concurrent monthly-job double-fire (ECS-Express cutover) cannot create two recurring advances.
-- Nullable: existing / manually-created supplements leave it NULL — MariaDB treats NULLs as distinct,
-- so multiple NULL source_reference rows coexist under the unique index.
ALTER TABLE salary_supplement ADD COLUMN IF NOT EXISTS source_reference VARCHAR(255) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_salary_supplement_source_ref ON salary_supplement (source_reference);
