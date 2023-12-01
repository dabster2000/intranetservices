package dk.trustworks.intranet.jobs;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.budgets.events.UpdateBudgetEvent;
import dk.trustworks.intranet.aggregates.sender.SystemEventSender;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.time.LocalDate;

@JBossLog
@ApplicationScoped
public class UpdateBudgetDocumentsJob {

    @Inject
    SlackService slackService;
    @Inject
    UserService userService;
    @Inject
    SystemEventSender systemEventSender;

    @Scheduled(every = "3h", delay = 1)
    public void refreshBudgetData() {
        System.out.println("prnt - BudgetServiceCache.refreshBudgetData");
        log.info("BudgetServiceCache.refreshBudgetData");

        log.info("Creating all budgets...");
        long l = System.currentTimeMillis();
        LocalDate lookupMonth = LocalDate.of(2014, 7, 1);
        //systemEventSender.handleEvent(new UpdateBudgetEvent(new DateRangeMap(lookupMonth, lookupMonth.plusMonths(1))));

        do {
            try {
                systemEventSender.handleEvent(new UpdateBudgetEvent(new DateRangeMap(lookupMonth, lookupMonth.plusMonths(1))));
            } catch (Exception e) {
                try {
                    log.error(e);
                    slackService.sendMessage(userService.findByUsername("hans.lassen", true), ExceptionUtils.getStackTrace(e));
                } catch (SlackApiException | IOException ex) {
                    throw new RuntimeException(ex);
                }
                throw new RuntimeException(e);
            }

            lookupMonth = lookupMonth.plusMonths(1);
        } while (lookupMonth.isBefore(LocalDate.now().plusYears(2)));



        log.info("...budgets created: "+(System.currentTimeMillis()-l));
    }
}
