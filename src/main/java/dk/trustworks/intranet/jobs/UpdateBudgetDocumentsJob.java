package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.aggregates.budgets.events.UpdateBudgetEvent;
import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.aggregates.sender.SystemEventSender;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class UpdateBudgetDocumentsJob {

    @Inject
    SystemEventSender systemEventSender;

    @Inject
    SNSEventSender snsEventSender;

    private final Map<LocalDate, LocalDateTime> tasks = new HashMap<>();
    private final int updateRateInHours = 3;
    private final int delayInSeconds = 10;

    //@Scheduled(every = "24h", delay = 0)
    @Scheduled(cron = "0 0 1 ? * 2-6")
    public void updateBiData() {
        log.info("BudgetServiceCache.updateBiData");
        LocalDate startMonth = LocalDate.now().minusMonths(2).withDayOfMonth(1);//LocalDate.of(2023, 11, 1);
        LocalDate endMonth = LocalDate.now().plusMonths(2).withDayOfMonth(1);
        for (User user : User.<User>streamAll().filter(user -> user.getType().equals("USER")).toList()) {
            log.info("Starting calculating availability for user: " + user.getUuid());
            LocalDate testDay = startMonth;
            do {
                snsEventSender.sendEvent(SNSEventSender.UserStatusUpdatePerDayTopic, user.getUuid(), testDay);
                snsEventSender.sendEvent(SNSEventSender.WorkUpdatePerDayTopic, user.getUuid(), testDay);
                snsEventSender.sendEvent(SNSEventSender.ContractConsultantUpdatePerDayTopic, user.getUuid(), testDay);
                // Log every quarter
                if(testDay.getMonthValue() % 3 == 0) log.info("UserStatusUpdatePerDayTopic: " + user.getUuid() + " - " + testDay);
                testDay = testDay.plusDays(1);
            } while (testDay.isBefore(endMonth));
        }
    }

    //@Scheduled(cron = "0 0 1 ? * 2-6")
    public void updateWorkData() {
        log.info("BudgetServiceCache.updateWorkData");
        LocalDate startMonth = LocalDate.of(2021, 7, 1);
        LocalDate endMonth = LocalDate.now().plusYears(1);
        int count = 0;
        for (User user : User.<User>streamAll().filter(user -> user.getType().equals("USER")).toList()) {
            log.info("Starting calculating availability for user "+(count++)+" : " + user.getUuid());
            LocalDate testDay = startMonth;
            do {
                snsEventSender.sendEvent(SNSEventSender.WorkUpdatePerDayTopic, user.getUuid(), testDay);
                // Log every half year
                //if(testDay.getMonthValue() % 6 == 0) log.info("WorkUpdatePerDayTopic: " + user.getUuid() + " - " + testDay);
                testDay = testDay.plusDays(1);
            } while (testDay.isBefore(endMonth));
        }
    }

    //@Scheduled(every = "24h", delay = 0)
    //@Scheduled(cron = "0 0 1 ? * 2-6")
    public void updateSalaryData() {
        log.info("BudgetServiceCache.updateSalaryData");
        LocalDate startMonth = LocalDate.of(2014, 6, 1);
        LocalDate endMonth = LocalDate.now().plusYears(1);
        int count = 0;
        for (User user : User.<User>streamAll().filter(user -> user.getType().equals("USER")).toList()) {
            log.info("Starting calculating salary for user "+(count++)+" : " + user.getUuid());
            LocalDate testDay = startMonth;
            do {
                snsEventSender.sendEvent(SNSEventSender.UserSalaryUpdatePerDayTopic, user.getUuid(), testDay);
                // Log every half year
                //if(testDay.getMonthValue() % 6 == 0) log.info("WorkUpdatePerDayTopic: " + user.getUuid() + " - " + testDay);
                testDay = testDay.plusDays(1);
            } while (testDay.isBefore(endMonth));
        }
    }

    //@Scheduled(every = "6h", delay = 0)
    //@Scheduled(cron = "0 0 5 ? * 2-6")
    public void createTasks() {
        log.info("BudgetServiceCache.createTasks");
        tasks.clear();
        LocalDate lookupMonth = LocalDate.of(2024, 7, 1);
        int i = 0;
        do {
            tasks.put(lookupMonth, LocalDateTime.now().minusHours(updateRateInHours).plusSeconds(((long) i*delayInSeconds)));
            snsEventSender.sendEvent(SNSEventSender.BudgetUpdateTopic, "", lookupMonth);
            i++;
            lookupMonth = lookupMonth.plusMonths(1);
        } while (lookupMonth.isBefore(LocalDate.now().plusYears(2)));
    }

    //@Scheduled(every = "20s", delay = 1)
    public void refreshBudgetData() {
        log.info("BudgetServiceCache.refreshBudgetData");
        tasks.values().stream().filter(localDateTime -> localDateTime.isBefore(LocalDateTime.now().minusHours(updateRateInHours))).forEach(localDateTime -> {
            LocalDate lookupMonth = tasks.entrySet().stream().filter(entry -> entry.getValue().equals(localDateTime)).findFirst().get().getKey();
            systemEventSender.handleEvent(new UpdateBudgetEvent(new DateRangeMap(lookupMonth, lookupMonth.plusMonths(1))));
            tasks.put(lookupMonth, LocalDateTime.now());
        });

    }
}
