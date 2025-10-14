# Batch Monitoring Debug Enhancements

## Problem
The batch monitoring system was not updating the database when jobs ran successfully. Failed jobs were tracked but successful ones weren't appearing in the `batch_job_execution_tracking` table.

## Root Cause Analysis
1. **Transaction Rollback**: The `onJobEnd()` method was using a regular `@Transactional` annotation, meaning it shared the job's transaction. If anything caused a rollback after job completion, the monitoring updates were lost.
2. **Silent Failures**: Methods were returning `null` without logging when tracking records weren't found.
3. **No Visibility**: Lack of detailed logging made it impossible to diagnose where the problem occurred.

## Changes Applied

### 1. Fixed Transaction Boundaries
- Changed `BatchJobTrackingService.onJobEnd()` to use `@Transactional(TxType.REQUIRES_NEW)`
- This ensures monitoring data persists even if the job's main transaction rolls back
- Added `em.flush()` calls to force immediate persistence

### 2. Added Comprehensive Logging
All logging uses the prefix `[BATCH-MONITOR]` or `[JOB-MONITOR]` for easy filtering.

#### BatchJobTrackingService
- **onJobStart**: Logs creation/update of tracking records
- **onJobEnd**: Logs status transitions and final results
- **findByExecutionIdForUpdate**: Logs when records aren't found (previously silent)
- **All update methods**: Log warnings when tracking record is missing

#### JobMonitoringListener  
- **beforeJob**: Logs job initialization and tracking setup
- **afterJob**: Logs job completion status and exception handling
- **Total subtasks**: Logs when setting expected work units

### 3. Added Exception Tracking
- Added `@BatchExceptionTracking` to `ProjectLockBatchlet`
- This ensures exceptions are captured even if the job framework misses them

## How to Debug

### 1. Enable Debug Logging
Add to `application.yml`:
```yaml
quarkus:
  log:
    category:
      "dk.trustworks.intranet.batch.monitoring":
        level: DEBUG
```

### 2. Monitor Logs
Watch for these key patterns:
```bash
# See all batch monitoring activity
tail -f logs/app.log | grep "BATCH-MONITOR\|JOB-MONITOR"

# Check for critical errors
tail -f logs/app.log | grep "BATCH-MONITOR.*CRITICAL"

# See transaction boundaries
tail -f logs/app.log | grep "BATCH-MONITOR.*onJob"
```

### 3. Verify Database Records
```sql
-- Check recent job executions
SELECT * FROM batch_job_execution_tracking 
ORDER BY start_time DESC 
LIMIT 10;

-- Check for incomplete records
SELECT * FROM batch_job_execution_tracking 
WHERE end_time IS NULL;

-- Check for failures
SELECT * FROM batch_job_execution_tracking 
WHERE result = 'FAILED';
```

### 4. Key Log Messages to Watch For

**Successful Flow:**
1. `[JOB-MONITOR] beforeJob() called for job 'X'`
2. `[BATCH-MONITOR] Creating new tracking record` or `Updating existing`
3. `[BATCH-MONITOR] Successfully created/updated tracking record`
4. `[JOB-MONITOR] afterJob() called for job 'X' - status: COMPLETED`
5. `[BATCH-MONITOR] onJobEnd called for execution X`
6. `[BATCH-MONITOR] Successfully updated job end for execution X with result: COMPLETED`

**Problem Indicators:**
- `[BATCH-MONITOR] CRITICAL: Cannot update job end - no tracking record found`
  - Means `onJobStart()` failed or record was deleted
- `[BATCH-MONITOR] Failed to find/lock tracking record`
  - Database lock timeout or record doesn't exist
- `[BATCH-MONITOR] Failed to create/update tracking record`
  - Database connection or constraint issue

## Testing the Fix

1. **Run a simple job:**
```bash
# Trigger mail-send job (runs every minute)
curl -X POST http://localhost:8080/admin/batch/trigger/mail-send
```

2. **Check the logs:**
```bash
grep "mail-send" logs/app.log | grep "BATCH-MONITOR"
```

3. **Verify in database:**
```sql
SELECT * FROM batch_job_execution_tracking 
WHERE job_name = 'mail-send' 
ORDER BY start_time DESC 
LIMIT 1;
```

## Expected Behavior After Fix

1. All jobs (successful or failed) create tracking records
2. Records persist even if job transaction rolls back  
3. Clear log trail showing the entire job lifecycle
4. `result` field correctly shows: COMPLETED, FAILED, or PARTIAL
5. `trace_log` contains stack traces for failed jobs

## Next Steps if Issues Persist

1. Check if Flyway migrations ran successfully
2. Verify database connectivity and permissions
3. Check for database triggers or constraints affecting the tracking table
4. Enable SQL logging to see actual INSERT/UPDATE statements
5. Check for competing transactions or deadlocks

## Rollback Plan

If the changes cause issues, revert:
1. Remove `TxType.REQUIRES_NEW` from `onJobEnd()` method
2. Remove added logging statements
3. Remove `@BatchExceptionTracking` annotations from batchlets