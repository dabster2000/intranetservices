package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.aggregates.budgets.events.UpdateBudgetEvent;
import dk.trustworks.intranet.aggregates.sender.SystemEventSender;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class UpdateBudgetDocumentsJob {

    @Inject
    SlackService slackService;
    @Inject
    UserService userService;
    @Inject
    SystemEventSender systemEventSender;

    private final Map<LocalDate, LocalDateTime> tasks = new HashMap<>();
    private final int updateRateInHours = 3;
    private final int delayInSeconds = 10;

    @Scheduled(every = "24h", delay = 0)
    public void createTasks() {
        log.info("BudgetServiceCache.createTasks");
        tasks.clear();
        LocalDate lookupMonth = LocalDate.of(2014, 7, 1);
        int i = 0;
        do {
            tasks.put(lookupMonth, LocalDateTime.now().minusHours(updateRateInHours).plusSeconds(((long) i*delayInSeconds)));
            i++;
            lookupMonth = lookupMonth.plusMonths(1);
        } while (lookupMonth.isBefore(LocalDate.now().plusYears(2)));
    }

    @Scheduled(every = "20s", delay = 1)
    public void refreshBudgetData() {
        log.info("BudgetServiceCache.refreshBudgetData");
        tasks.values().stream().filter(localDateTime -> localDateTime.isBefore(LocalDateTime.now().minusHours(updateRateInHours))).forEach(localDateTime -> {
            LocalDate lookupMonth = tasks.entrySet().stream().filter(entry -> entry.getValue().equals(localDateTime)).findFirst().get().getKey();
            systemEventSender.handleEvent(new UpdateBudgetEvent(new DateRangeMap(lookupMonth, lookupMonth.plusMonths(1))));
            tasks.put(lookupMonth, LocalDateTime.now());
        });

    }
}
