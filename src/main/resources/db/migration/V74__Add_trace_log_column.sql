ALTER TABLE batch_job_execution_tracking
    ADD COLUMN trace_log MEDIUMTEXT NULL AFTER details;
