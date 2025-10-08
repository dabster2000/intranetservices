# Batch Monitoring Execution ID Reset Fix

## The Critical Problem

JBatch execution IDs reset to 1 after every server restart, causing:
- **Data Corruption**: New job runs overwrote historical records
- **Lost History**: Previous job execution data was silently replaced
- **Incorrect Metrics**: Job statistics became meaningless

## Root Cause

1. JBatch uses an **in-memory counter** for execution IDs (not persistent)
2. The database had a **UNIQUE constraint** on execution_id
3. The code **updated existing records** when it found matching execution_ids
4. Result: After restart, execution_id=1 overwrote the old execution_id=1 record

## Solution Implemented

### 1. Database Schema Change (V75 Migration)
```sql
-- Removed UNIQUE constraint
ALTER TABLE batch_job_execution_tracking DROP INDEX execution_id;

-- Added performance indexes
CREATE INDEX idx_execution_id ON batch_job_execution_tracking(execution_id);
CREATE INDEX idx_execution_current ON batch_job_execution_tracking(execution_id, start_time DESC, end_time);
```

### 2. Code Changes to BatchJobTrackingService

#### onJobStart - ALWAYS INSERT
```java
@Transactional(TxType.REQUIRES_NEW)
public void onJobStart(long executionId, String jobName) {
    // ALWAYS create new record - NEVER update
    BatchJobExecutionTracking e = new BatchJobExecutionTracking();
    e.setExecutionId(executionId);
    e.setJobName(jobName);
    e.setStatus("STARTED");
    e.setStartTime(LocalDateTime.now());
    em.persist(e);  // Always INSERT, never MERGE
}
```

#### findByExecutionIdForUpdate - Find Active Record
```java
// Finds the most recent record with this execution_id that hasn't ended
"SELECT e FROM BatchJobExecutionTracking e " +
"WHERE e.executionId = :id " +
"AND e.endTime IS NULL " +  // Only active jobs
"ORDER BY e.startTime DESC"  // Most recent if multiple
```

### 3. Transaction Annotations Verified

| Method | Transaction Type | Purpose |
|--------|-----------------|---------|
| `onJobStart` | REQUIRES_NEW | Ensures job start is recorded even if main tx fails |
| `onJobEnd` | REQUIRES_NEW | Ensures job completion is recorded independently |
| `onJobFailure` | REQUIRES_NEW | Ensures failures are always recorded |
| `setTrace` | REQUIRES_NEW | Independent transaction for error logging |
| `incrementTotalSubtasks` | REQUIRED | Participates in caller's transaction |
| `incrementCompletedSubtasks` | REQUIRED | Participates in caller's transaction |
| `appendDetails` | REQUIRED | Participates in caller's transaction |
| `setTotalSubtasks` | REQUIRED | Participates in caller's transaction |

## How It Works Now

### Before Fix (Data Corruption)
```
Server Run 1:
  Job A runs → execution_id=1 → Creates record ID=100
  Job B runs → execution_id=2 → Creates record ID=101

[SERVER RESTART - Counter resets]

Server Run 2:
  Job C runs → execution_id=1 → UPDATES record ID=100 (Job A's data lost!)
  Job D runs → execution_id=2 → UPDATES record ID=101 (Job B's data lost!)
```

### After Fix (Preserves History)
```
Server Run 1:
  Job A runs → execution_id=1 → Creates record ID=100
  Job B runs → execution_id=2 → Creates record ID=101

[SERVER RESTART - Counter resets]

Server Run 2:
  Job C runs → execution_id=1 → Creates NEW record ID=102
  Job D runs → execution_id=2 → Creates NEW record ID=103
```

## Testing the Fix

### 1. Apply the Migration
```bash
# The migration will run automatically on startup
# Or manually verify:
./mvnw quarkus:dev
```

### 2. Verify Multiple Records Allowed
```sql
-- Should show multiple records with same execution_id after restart
SELECT execution_id, job_name, start_time, id 
FROM batch_job_execution_tracking 
WHERE execution_id IN (1,2,3)
ORDER BY id DESC;
```

### 3. Test Server Restart Scenario
```bash
# Start server and run some jobs
./mvnw quarkus:dev

# Wait for a few jobs to run, note the execution IDs

# Stop server (Ctrl+C)

# Restart server
./mvnw quarkus:dev

# Check database - should see NEW records, not updated old ones
```

### 4. Verify Correct Record Updates
```sql
-- Active jobs (being updated)
SELECT * FROM batch_job_execution_tracking 
WHERE end_time IS NULL 
ORDER BY start_time DESC;

-- Completed jobs (preserved history)
SELECT * FROM batch_job_execution_tracking 
WHERE end_time IS NOT NULL 
ORDER BY id DESC LIMIT 20;
```

## Monitoring

### Check for Data Integrity
```sql
-- Find duplicate execution_ids (expected after fix)
SELECT execution_id, COUNT(*) as count, 
       MIN(start_time) as first_run, 
       MAX(start_time) as last_run
FROM batch_job_execution_tracking
GROUP BY execution_id
HAVING COUNT(*) > 1
ORDER BY count DESC;
```

### Check Job History Preservation
```sql
-- Verify historical records remain unchanged
SELECT id, execution_id, job_name, start_time, end_time
FROM batch_job_execution_tracking
WHERE DATE(start_time) < CURDATE()
ORDER BY id DESC;
```

## Rollback Plan (If Needed)

1. **Restore UNIQUE constraint** (NOT RECOMMENDED - will break after restart):
```sql
ALTER TABLE batch_job_execution_tracking 
ADD UNIQUE INDEX execution_id (execution_id);
```

2. **Revert code changes**:
- Restore the old `onJobStart` logic
- Restore the old `findByExecutionIdForUpdate` query

**WARNING**: Rolling back will resume data corruption on server restarts.

## Long-term Recommendations

Consider implementing a persistent JBatch repository:
- Configure JBatch to use database-backed job repository
- Execution IDs would persist across restarts
- More complex but eliminates the root cause

## Summary

✅ **Fixed**: Historical job data is now preserved across server restarts
✅ **Fixed**: Each job run creates a new tracking record
✅ **Fixed**: Updates find the correct active record
✅ **Verified**: Transaction boundaries ensure data consistency
✅ **Added**: Comprehensive logging for troubleshooting

The system now correctly handles JBatch's execution ID reset behavior without data loss.