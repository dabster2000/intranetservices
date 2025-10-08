package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.batch.monitoring.BatchletResult;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import dk.trustworks.intranet.recalc.DayRecalcService;
import dk.trustworks.intranet.recalc.RecalcResult;
import dk.trustworks.intranet.recalc.RecalcTrigger;
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

    @Inject DayRecalcService recalcService;
    @Inject StepContext stepContext;

    @Inject @BatchProperty(name = "userUuid") String userUuid;
    @Inject @BatchProperty(name = "day") String dayIso;
    @Inject @BatchProperty(name = "trigger") String triggerName;
    
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
                stepContext.setTransientUserData(executionResult);
                return "FAILED";
            }
            
            final LocalDate day = LocalDate.parse(dayIso);

            RecalcTrigger trigger;
            try {
                trigger = (triggerName != null && !triggerName.isBlank()) ? RecalcTrigger.valueOf(triggerName) : RecalcTrigger.SCHEDULED_BI;
            } catch (IllegalArgumentException ex) {
                log.warnf("Unknown trigger '%s', defaulting to SCHEDULED_BI", triggerName);
                trigger = RecalcTrigger.SCHEDULED_BI;
            }

            RecalcResult r = recalcService.recalc(userUuid, day, trigger);
            boolean resultFailed = r.isFailed();
            
            long processingTime = System.currentTimeMillis() - startTime;

            if (resultFailed) {
                executionResult = BatchletResult.partial("Partial success: " + r.summary());
                executionResult.setPartitionId(partitionId);
                executionResult.setProcessingTimeMs(processingTime);
                stepContext.setTransientUserData(executionResult);
                return "PARTIAL";
            } else {
                executionResult = BatchletResult.success("Successfully processed user " + userUuid + " for " + day + "; " + r.summary());
                executionResult.setPartitionId(partitionId);
                executionResult.setProcessingTimeMs(processingTime);
                stepContext.setTransientUserData(executionResult);
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
            stepContext.setTransientUserData(executionResult);
            return "FAILED";
        }
    }
    
    @Override
    public Serializable collectPartitionData() throws Exception {
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
        String partitionId = (userUuid != null ? userUuid : "unknownUser") + "_" + (dayIso != null ? dayIso : "unknownDay");
        BatchletResult fallback = BatchletResult.failure("No execution result was produced for this partition");
        fallback.setPartitionId(partitionId);
        return fallback;
    }
}