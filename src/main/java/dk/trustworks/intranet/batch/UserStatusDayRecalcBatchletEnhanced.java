package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.monitoring.AbstractEnhancedBatchlet;
import dk.trustworks.intranet.batch.monitoring.BatchletResult;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;

/**
 * Enhanced batchlet to recalculate user data for a specific day after a status change.
 * This ensures that status changes properly propagate to all affected calculations
 * including availability, work aggregates, and budgets.
 * Uses return-based tracking to avoid race conditions.
 */
@Dependent
@Named("userStatusDayRecalcBatchletEnhanced")
@JBossLog
public class UserStatusDayRecalcBatchletEnhanced extends AbstractEnhancedBatchlet {

    @Inject
    UserAvailabilityCalculatorService availability;
    
    @Inject
    WorkAggregateService workAggregates;
    
    @Inject
    BudgetCalculatingExecutor budgets;

    @Inject @BatchProperty(name = "userUuid")
    String userUuid;

    @Inject @BatchProperty(name = "date")
    String dateStr;
    
    @Override
    protected String generatePartitionId() {
        return userUuid + "_" + dateStr;
    }

    @Override
    @ActivateRequestContext
    @Transactional(TxType.REQUIRES_NEW)
    protected BatchletResult performWork(String partitionId) throws Exception {
        try {
            if (userUuid == null || userUuid.isBlank() || dateStr == null || dateStr.isBlank()) {
                return BatchletResult.failure(
                    "Missing partition properties: userUuid=" + userUuid + ", date=" + dateStr,
                    new jakarta.batch.operations.BatchRuntimeException(
                        "Missing partition properties: userUuid=" + userUuid + ", date=" + dateStr)
                );
            }
            
            LocalDate date = LocalDate.parse(dateStr);
            
            // Track individual operation failures but continue processing
            boolean hasErrors = false;
            StringBuilder errorMessages = new StringBuilder();
            
            // 1. Update availability based on current status
            if (!executeOperation("Availability update", 
                () -> availability.updateUserAvailabilityByDay(userUuid, date), 
                errorMessages)) {
                hasErrors = true;
            }
            
            // 2. Recalculate work aggregates
            if (!executeOperation("Work aggregates recalculation",
                () -> workAggregates.recalculateWork(userUuid, date),
                errorMessages)) {
                hasErrors = true;
            }
            
            // 3. Recalculate budgets (which depend on availability and work)
            if (!executeOperation("Budget recalculation",
                () -> budgets.recalculateUserDailyBudgets(userUuid, date),
                errorMessages)) {
                hasErrors = true;
            }
            
            if (hasErrors) {
                return BatchletResult.partial("Partial success for user " + userUuid + 
                    " on " + date + ": " + errorMessages.toString());
            } else {
                return BatchletResult.success("Successfully processed all updates for user " + 
                    userUuid + " on " + date);
            }
            
        } catch (Exception e) {
            log.errorf(e, "Fatal error processing user status updates for %s on %s", userUuid, dateStr);
            return BatchletResult.failure(
                "Fatal error processing user status updates for " + userUuid + " on " + dateStr,
                e
            );
        }
    }
}