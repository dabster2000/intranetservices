-- Drop the UNIQUE constraint on execution_id to handle JBatch ID resets after server restart
-- execution_id is only unique within a JVM instance, not globally
-- This allows multiple records with same execution_id (from different server runs)
ALTER TABLE batch_job_execution_tracking DROP INDEX execution_id;

-- Add index for performance when querying by execution_id
-- We still need to efficiently find records by execution_id
CREATE INDEX idx_execution_id ON batch_job_execution_tracking(execution_id);

-- Add composite index for finding the current active job record
-- (execution_id + start_time descending + end_time null check)
CREATE INDEX idx_execution_current ON batch_job_execution_tracking(execution_id, start_time DESC, end_time);