-- Test Script for Batch Monitoring Fix
-- Run these queries to verify the fix is working correctly

-- 1. Check for duplicate execution_ids (EXPECTED after fix)
-- This proves historical records are preserved
SELECT 
    execution_id,
    COUNT(*) as occurrences,
    GROUP_CONCAT(DISTINCT job_name) as job_names,
    MIN(start_time) as first_occurrence,
    MAX(start_time) as latest_occurrence,
    COUNT(DISTINCT DATE(start_time)) as different_days
FROM batch_job_execution_tracking
GROUP BY execution_id
HAVING COUNT(*) > 1
ORDER BY execution_id;

-- 2. Verify no records are being overwritten
-- Check that old records still have their original timestamps
SELECT 
    id,
    execution_id,
    job_name,
    start_time,
    end_time,
    status,
    result,
    TIMESTAMPDIFF(SECOND, start_time, IFNULL(end_time, NOW())) as duration_seconds
FROM batch_job_execution_tracking
WHERE execution_id <= 10  -- Low execution IDs that would be reused
ORDER BY id DESC
LIMIT 20;

-- 3. Find currently running jobs
-- These should have end_time NULL
SELECT 
    id,
    execution_id,
    job_name,
    start_time,
    status,
    progress_percent,
    completed_subtasks,
    total_subtasks
FROM batch_job_execution_tracking
WHERE end_time IS NULL
ORDER BY start_time DESC;

-- 4. Verify jobs complete properly
-- Recent completed jobs should have proper end times and results
SELECT 
    id,
    execution_id,
    job_name,
    start_time,
    end_time,
    result,
    exit_status,
    TIMESTAMPDIFF(SECOND, start_time, end_time) as duration_seconds
FROM batch_job_execution_tracking
WHERE end_time IS NOT NULL
ORDER BY end_time DESC
LIMIT 10;

-- 5. Check for data integrity issues
-- Look for impossible scenarios
SELECT 'Jobs with end_time before start_time' as issue, COUNT(*) as count
FROM batch_job_execution_tracking
WHERE end_time < start_time
UNION ALL
SELECT 'Jobs with NULL start_time' as issue, COUNT(*) as count  
FROM batch_job_execution_tracking
WHERE start_time IS NULL
UNION ALL
SELECT 'Jobs with invalid status' as issue, COUNT(*) as count
FROM batch_job_execution_tracking
WHERE status NOT IN ('STARTED', 'STARTING', 'STOPPING', 'STOPPED', 'FAILED', 'COMPLETED', 'ABANDONED')
UNION ALL
SELECT 'Completed jobs without end_time' as issue, COUNT(*) as count
FROM batch_job_execution_tracking
WHERE result = 'COMPLETED' AND end_time IS NULL;

-- 6. Summary statistics
SELECT 
    'Total Records' as metric,
    COUNT(*) as value
FROM batch_job_execution_tracking
UNION ALL
SELECT 
    'Unique Execution IDs' as metric,
    COUNT(DISTINCT execution_id) as value
FROM batch_job_execution_tracking
UNION ALL
SELECT 
    'Currently Running' as metric,
    COUNT(*) as value
FROM batch_job_execution_tracking
WHERE end_time IS NULL
UNION ALL
SELECT 
    'Failed Jobs' as metric,
    COUNT(*) as value
FROM batch_job_execution_tracking
WHERE result = 'FAILED'
UNION ALL
SELECT 
    'Successful Jobs' as metric,
    COUNT(*) as value
FROM batch_job_execution_tracking
WHERE result = 'COMPLETED';

-- 7. Execution ID reuse pattern
-- Shows how often each execution_id has been reused
SELECT 
    CASE 
        WHEN COUNT(*) = 1 THEN 'Used Once'
        WHEN COUNT(*) = 2 THEN 'Used Twice'  
        WHEN COUNT(*) = 3 THEN 'Used 3 Times'
        WHEN COUNT(*) >= 4 THEN 'Used 4+ Times'
    END as reuse_pattern,
    COUNT(DISTINCT execution_id) as execution_ids,
    SUM(COUNT(*)) OVER() as total_records
FROM batch_job_execution_tracking
GROUP BY execution_id
GROUP BY reuse_pattern
ORDER BY 
    CASE reuse_pattern
        WHEN 'Used Once' THEN 1
        WHEN 'Used Twice' THEN 2
        WHEN 'Used 3 Times' THEN 3
        WHEN 'Used 4+ Times' THEN 4
    END;