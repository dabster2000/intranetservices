package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.batch.monitoring.BatchletResult;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.partition.PartitionCollector;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.time.LocalDate;

@Named("userDayBatchletEnhanced")
@Dependent
@BatchExceptionTracking
@JBossLog
public class UserDayBatchletEnhanced extends AbstractBatchlet implements PartitionCollector {

    @Inject UserAvailabilityCalculatorService availability;
    @Inject WorkAggregateService workAggregates;
    @Inject BudgetCalculatingExecutor budgets;
    @Inject StepContext stepContext;

    @Inject @BatchProperty(name = "userUuid") String userUuid;
    @Inject @BatchProperty(name = "day") String dayIso;
    
    private BatchletResult executionResult;
    private long startTime;

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public String process() {
        startTime = System.currentTimeMillis();
        String partitionId = userUuid + "_" + dayIso;
        
        try {
            if (userUuid == null || userUuid.isBlank() || dayIso == null || dayIso.isBlank()) {
                executionResult = BatchletResult.failure(
                    "Missing partition properties: userUuid=" + userUuid + ", day=" + dayIso,
                    new jakarta.batch.operations.BatchRuntimeException(
                        "Missing partition properties: userUuid=" + userUuid + ", day=" + dayIso)
                );
                executionResult.setPartitionId(partitionId);
                return "FAILED";
            }
            
            final LocalDate day = LocalDate.parse(dayIso);
            
            // Track individual operation failures but continue processing
            boolean hasErrors = false;
            StringBuilder errorMessages = new StringBuilder();
            
            try {
                availability.updateUserAvailabilityByDay(userUuid, day);
                log.debugf("Successfully updated availability for user %s on %s", userUuid, day);
            } catch (Exception e) {
                hasErrors = true;
                errorMessages.append("Availability update failed: ").append(e.getMessage()).append("; ");
                log.errorf(e, "Failed to update availability for user %s on %s", userUuid, day);
            }
            
            try {
                workAggregates.recalculateWork(userUuid, day);
                log.debugf("Successfully recalculated work for user %s on %s", userUuid, day);
            } catch (Exception e) {
                hasErrors = true;
                errorMessages.append("Work recalculation failed: ").append(e.getMessage()).append("; ");
                log.errorf(e, "Failed to recalculate work for user %s on %s", userUuid, day);
            }
            
            try {
                budgets.recalculateUserDailyBudgets(userUuid, day);
                log.debugf("Successfully recalculated budgets for user %s on %s", userUuid, day);
            } catch (Exception e) {
                hasErrors = true;
                errorMessages.append("Budget recalculation failed: ").append(e.getMessage()).append("; ");
                log.errorf(e, "Failed to recalculate budgets for user %s on %s", userUuid, day);
            }
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            if (hasErrors) {
                executionResult = BatchletResult.partial("Partial success: " + errorMessages.toString());
                executionResult.setPartitionId(partitionId);
                executionResult.setProcessingTimeMs(processingTime);
                return "PARTIAL";
            } else {
                executionResult = BatchletResult.success("Successfully processed user " + userUuid + " for " + day);
                executionResult.setPartitionId(partitionId);
                executionResult.setProcessingTimeMs(processingTime);
                return "COMPLETED";
            }
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.errorf(e, "Fatal error processing user %s for day %s", userUuid, dayIso);
            executionResult = BatchletResult.failure(
                "Fatal error processing user " + userUuid + " for day " + dayIso,
                e
            );
            executionResult.setPartitionId(partitionId);
            executionResult.setProcessingTimeMs(processingTime);
            return "FAILED";
        }
    }
    
    @Override
    public Serializable collectPartitionData() throws Exception {
        // This is called after process() to collect results from this partition
        return executionResult;
    }
}