package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.Batchlet;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.LocalDate;

/**
 * Batchlet to recalculate user data for a specific day after a status change.
 * This ensures that status changes properly propagate to all affected calculations
 * including availability, work aggregates, and budgets.
 */
@Dependent
@Named("userStatusDayRecalcBatchlet")
@BatchExceptionTracking
public class UserStatusDayRecalcBatchlet implements Batchlet {

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
    @ActivateRequestContext // ensures request scoped beans are available if used
    @Transactional(TxType.REQUIRES_NEW) // strong isolation per partition/day
    public String process() throws Exception {
        LocalDate date = LocalDate.parse(dateStr);
        
        // Recalculate in order - same as UserDayBatchlet
        // 1. Update availability based on current status
        availability.updateUserAvailabilityByDay(userUuid, date);
        
        // 2. Recalculate work aggregates
        workAggregates.recalculateWork(userUuid, date);
        
        // 3. Recalculate budgets (which depend on availability and work)
        budgets.recalculateUserDailyBudgets(userUuid, date);
        
        return "OK";
    }

    @Override
    public void stop() throws Exception {
        // no-op - graceful shutdown if needed
    }
}