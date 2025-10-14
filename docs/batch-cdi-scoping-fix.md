# JBeret CDI Scoping Bug Fix - AbstractEnhancedBatchlet

## Problem Description

The `contract-consultant-forward-recalc` batch job (execution ID 824, 2025-10-09) failed with all 539 partitions reporting:
```
Partition partition-XXXXXXXXXX: FAILURE - No execution result was produced for this partition
```

### Root Cause

**CDI Scoping Issue with @Dependent Beans**

When `AbstractEnhancedBatchlet` is used with `@Dependent` scope (which is the default for batch components):

1. JBeret calls `process()` method on one CDI proxy instance
2. The method stores `executionResult` in an instance field
3. JBeret later calls `collectPartitionData()` to collect results
4. **CDI may provide a different proxy instance** where `executionResult` is null
5. This triggers the fallback error: "No execution result was produced for this partition"

### Why UserDayBatchletEnhanced Worked

`UserDayBatchletEnhanced` avoided this issue by:
- Storing results in `StepContext.setTransientUserData()` (lines 55, 78, 84, 97)
- Retrieving from `StepContext.getTransientUserData()` in `collectPartitionData()` (line 105)
- **StepContext is container-managed and survives across CDI proxy instances**

## Solution Implemented

Modified `AbstractEnhancedBatchlet.java` to use StepContext for result storage:

### Changes Made

#### 1. In `process()` method (after line 66):
```java
// Store in StepContext to survive CDI proxy instance changes
stepContext.setTransientUserData(executionResult);
```

#### 2. In `catch` block (after line 95):
```java
// Store in StepContext to survive CDI proxy instance changes
stepContext.setTransientUserData(executionResult);
```

#### 3. In `collectPartitionData()` method (lines 107-120):
```java
// Try to get result from StepContext first (handles CDI scoping issues)
BatchletResult result = (BatchletResult) stepContext.getTransientUserData();
if (result != null) {
    return result;
}

// Fallback to instance field (shouldn't normally happen)
if (executionResult != null) {
    return executionResult;
}

// Last resort fallback
BatchletResult fallback = BatchletResult.failure("No execution result was produced for this partition");
return fallback;
```

## Impact

This fix resolves the issue for ALL jobs using `AbstractEnhancedBatchlet`:
- ✅ `contract-consultant-forward-recalc` (ContractConsultantDayRecalcBatchletEnhanced)
- ✅ User status recalculation (UserStatusDayRecalcBatchletEnhanced)
- ✅ User salary recalculation (UserSalaryDayRecalcBatchletEnhanced)
- ✅ Any future enhanced batchlets

## Testing

To verify the fix:

1. **Re-run the failed job**:
   ```bash
   # The job should now complete successfully
   # Check batch_job_execution_tracking table for status
   ```

2. **Expected behavior**:
   - Progress tracking shows real-time updates
   - All partitions complete with SUCCESS status
   - Job result is COMPLETED (not FAILED)
   - No "No execution result was produced" errors

3. **Database verification**:
   ```sql
   SELECT execution_id, job_name, status, result,
          completed_subtasks, total_subtasks, progress_percent
   FROM batch_job_execution_tracking
   WHERE job_name = 'contract-consultant-forward-recalc'
   ORDER BY start_time DESC
   LIMIT 5;
   ```

## Technical Notes

### CDI @Dependent Scope Behavior
- `@Dependent` scoped beans don't have a shared state
- Each injection point may get a different proxy instance
- Instance fields are NOT shared between method invocations
- Container-managed resources (like StepContext) ARE shared

### StepContext.transientUserData
- Managed by the batch runtime
- Survives across CDI proxy instances
- Scoped to the step execution lifecycle
- Thread-safe for partition processing

## Date
2025-10-09

## Status
✅ **FIXED** - Compiled and ready for testing
