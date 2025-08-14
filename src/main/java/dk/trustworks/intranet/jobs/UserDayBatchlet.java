package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import java.time.LocalDate;

@Named("userDayBatchlet")
@Dependent
public class UserDayBatchlet extends AbstractBatchlet {

    @Inject UserAvailabilityCalculatorService availability;
    @Inject
    WorkAggregateService workAggregates;
    @Inject BudgetCalculatingExecutor budgets;

    @Inject @BatchProperty(name = "userUuid") String userUuid;
    @Inject @BatchProperty(name = "day")      String dayIso;

    // Keep the 3 operations in-order & in a single transaction per (user,day).
    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public String process() {
        if (userUuid == null || userUuid.isBlank() || dayIso == null || dayIso.isBlank()) {
            throw new jakarta.batch.operations.BatchRuntimeException(
                    "Missing partition properties: userUuid=" + userUuid + ", day=" + dayIso);
        }
        final LocalDate day = LocalDate.parse(dayIso); // expects ISO yyyy-MM-dd
        // 1) 2) 3) run in order
        availability.updateUserAvailabilityByDay(userUuid, day);
        workAggregates.recalculateWork(userUuid, day);
        budgets.recalculateUserDailyBudgets(userUuid, day);
        return "COMPLETED";
    }
}