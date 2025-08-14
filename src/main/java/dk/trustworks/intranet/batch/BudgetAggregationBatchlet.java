package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.bi.services.UserAvailabilityCalculatorService;
import dk.trustworks.intranet.bi.services.WorkAggregateService;
import dk.trustworks.intranet.bi.services.BudgetCalculatingExecutor;
import dk.trustworks.intranet.userservice.model.User;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.stream.Stream;

@JBossLog
@Named("budgetAggregationBatchlet")
@Dependent
public class BudgetAggregationBatchlet extends AbstractBatchlet {

    @Inject
    UserAvailabilityCalculatorService userAvailabilityCalculatorService;

    @Inject
    WorkAggregateService workAggregateService;

    @Inject
    BudgetCalculatingExecutor budgetCalculatingExecutor;

    @Inject
    @BatchProperty(name = "startMonth")
    String startMonthProp;

    @Override
    public String process() throws Exception {
        LocalDate startMonth = resolveStartMonth();
        LocalDate endMonth = LocalDate.now().plusMonths(2).withDayOfMonth(1);

        log.infof("Starting budget aggregation batchlet: startMonth=%s endMonth=%s", startMonth, endMonth);

        try (Stream<User> users = User.<User>streamAll()) {
            users.filter(u -> "USER".equals(u.getType()))
                 .forEach(user -> {
                     log.infof("Processing user %s", user.getUuid());
                     LocalDate day = startMonth;
                     while (day.isBefore(endMonth)) {
                         try {
                             userAvailabilityCalculatorService.updateUserAvailabilityByDay(user.getUuid(), day);
                             workAggregateService.recalculateWork(user.getUuid(), day);
                             budgetCalculatingExecutor.recalculateUserDailyBudgets(user.getUuid(), day);
                         } catch (Exception e) {
                             log.errorf(e, "Error processing user %s on day %s", user.getUuid(), day);
                         }
                         day = day.plusDays(1);
                     }
                 });
        }

        log.info("Budget aggregation batchlet completed");
        return "COMPLETED";
    }

    private LocalDate resolveStartMonth() {
        try {
            if (startMonthProp != null && !startMonthProp.isBlank()) {
                return LocalDate.parse(startMonthProp).withDayOfMonth(1);
            }
        } catch (Exception e) {
            log.warnf("Invalid startMonth property '%s', falling back to default", startMonthProp);
        }
        return LocalDate.now().minusMonths(2).withDayOfMonth(1);
    }
}
