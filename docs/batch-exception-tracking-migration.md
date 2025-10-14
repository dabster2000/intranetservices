# Batch Exception Tracking Migration Guide

## Overview

This guide explains how to add exception tracking to existing batch jobs so that stack traces are properly captured in the `trace_log` column of the `batch_job_execution_tracking` table.

## Problem Statement

Previously, when batch jobs like `birthday-notification` and `bi-date-update` failed, only the failure status was recorded but no stack trace was saved, making debugging difficult.

## Solution Architecture

The solution provides multiple mechanisms to capture exceptions:

1. **ThreadLocal Registry** - Passes exceptions across JBeret boundaries
2. **MonitoredBatchlet Base Class** - Automatic exception capture for new jobs
3. **CDI Interceptor** - Retrofit existing jobs without code changes
4. **Enhanced JobListener** - Retrieves and persists captured exceptions

## Migration Options

### Option 1: Minimal Change (Add Annotation)

For existing batchlets, simply add the `@BatchExceptionTracking` annotation:

```java
@Named("birthdayNotificationBatchlet")
@Dependent
@BatchExceptionTracking  // <-- Add this line
public class BirthdayNotificationBatchlet extends AbstractBatchlet {
    // No other changes needed
    @Override
    public String process() throws Exception {
        // Existing code remains unchanged
    }
}
```

### Option 2: Better Approach (Extend MonitoredBatchlet)

For more comprehensive tracking, refactor to extend `MonitoredBatchlet`:

```java
@Named("birthdayNotificationBatchlet")
@Dependent
public class BirthdayNotificationBatchlet extends MonitoredBatchlet {  // <-- Change parent
    
    @Override
    protected String doProcess() throws Exception {  // <-- Rename method
        // Move existing process() logic here
        // All exceptions will be automatically captured
    }
    
    @Override
    protected void onFinally(long executionId, String jobName) {
        // Optional: Add cleanup logic
    }
}
```

### Option 3: Manual Integration

For complex scenarios requiring fine control:

```java
@Named("complexBatchlet")
@Dependent
public class ComplexBatchlet extends AbstractBatchlet {
    
    @Inject
    private JobContext jobContext;
    
    @Inject
    private BatchExceptionRegistry exceptionRegistry;
    
    @Override
    public String process() throws Exception {
        long executionId = jobContext.getExecutionId();
        
        try {
            // Your batch logic
            performWork();
            return "COMPLETED";
            
        } catch (Exception e) {
            // Manually capture the exception
            exceptionRegistry.captureException(executionId, e);
            throw e;  // Re-throw to maintain batch semantics
        }
    }
}
```

## Migration Steps for Specific Jobs

### Birthday Notification Job

1. Open `BirthdayNotificationBatchlet.java`
2. Add `@BatchExceptionTracking` annotation to the class
3. No other changes needed

### BI Date Update Job

1. Open `UserDayBatchlet.java` (the batchlet used in bi-date-update)
2. Either:
   - Add `@BatchExceptionTracking` annotation, OR
   - Change to extend `MonitoredBatchlet` and rename `process()` to `doProcess()`

### Mail Send Job

1. Open `MailSendBatchlet.java`
2. Add `@BatchExceptionTracking` annotation
3. Optionally add progress tracking for better monitoring

## Testing Exception Tracking

### Manual Test

1. Trigger the test job:
```bash
curl -X POST "http://localhost:9093/batch/test/exception?type=nullpointer" \
  -H "Authorization: Bearer $JWT_TOKEN"
```

2. Check the status:
```bash
curl "http://localhost:9093/batch/test/exception/status/{executionId}" \
  -H "Authorization: Bearer $JWT_TOKEN"
```

3. Verify in database:
```sql
SELECT job_name, status, result, trace_log 
FROM batch_job_execution_tracking 
WHERE job_name = 'test-exception-tracking'
ORDER BY start_time DESC
LIMIT 1;
```

### Validation Endpoint

Run the automated validation:
```bash
curl "http://localhost:9093/batch/test/exception/validate" \
  -H "Authorization: Bearer $JWT_TOKEN"
```

## Benefits

1. **Complete Stack Traces** - Full exception details including nested causes
2. **Transaction Safety** - Traces persist even if job transaction rolls back
3. **Multiple Capture Points** - Redundant mechanisms ensure no exceptions are lost
4. **Zero Runtime Impact** - For jobs that don't fail
5. **Backward Compatible** - Existing jobs continue to work without modification

## Best Practices

1. **New Jobs** - Always extend `MonitoredBatchlet`
2. **Existing Jobs** - Add `@BatchExceptionTracking` as a quick fix
3. **Critical Jobs** - Refactor to extend `MonitoredBatchlet` for better control
4. **Testing** - Use the test endpoints to verify tracking after migration
5. **Monitoring** - Check trace_log column regularly for recurring issues

## Troubleshooting

### No Trace Captured

1. Verify the job has `jobMonitoringListener` in its XML configuration
2. Check that CDI is properly initialized (use `@ActivateRequestContext` if needed)
3. Ensure the exception is thrown from the batchlet's process method

### Partial Trace

1. Check database column size (should be MEDIUMTEXT)
2. Verify transaction boundaries (use REQUIRES_NEW for trace persistence)

### Performance Issues

1. Exception capture is only active during failures
2. Stack trace generation has minimal overhead
3. Consider async persistence for high-frequency jobs

## Implementation Components

- `BatchExceptionRegistry.java` - ThreadLocal and map-based exception storage
- `MonitoredBatchlet.java` - Base class with automatic exception capture
- `BatchExceptionInterceptor.java` - CDI interceptor for annotation-based tracking
- `JobMonitoringListener.java` - Enhanced to retrieve and persist exceptions
- `BatchJobTrackingService.java` - Already has methods for trace persistence

## SQL Verification

Check that traces are being captured:

```sql
-- Find recent failures with traces
SELECT 
    job_name,
    execution_id,
    status,
    result,
    start_time,
    end_time,
    exit_status,
    CASE 
        WHEN trace_log IS NOT NULL THEN 'YES' 
        ELSE 'NO' 
    END as has_trace,
    LENGTH(trace_log) as trace_size
FROM batch_job_execution_tracking
WHERE status = 'FAILED' OR result = 'FAILED'
ORDER BY start_time DESC
LIMIT 20;

-- View trace for specific execution
SELECT trace_log 
FROM batch_job_execution_tracking 
WHERE execution_id = ?;
```

## Next Steps

1. Apply `@BatchExceptionTracking` to all critical batch jobs
2. Gradually refactor to extend `MonitoredBatchlet` for new features
3. Set up alerts based on trace_log content patterns
4. Create dashboards showing jobs with recurring exceptions
5. Implement automatic retry logic for transient failures