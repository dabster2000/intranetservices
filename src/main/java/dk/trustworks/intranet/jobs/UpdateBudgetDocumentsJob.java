package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
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
@Deprecated
public class UpdateBudgetDocumentsJob {

    @Inject
    SNSEventSender snsEventSender;

    private final Map<LocalDate, LocalDateTime> tasks = new HashMap<>();
    private final int updateRateInHours = 3;
    private final int delayInSeconds = 10;

    //@Scheduled(cron = "0 0 1 ? * 2-6") // Disabled: replaced by JBeret job 'budget-aggregation' triggered via BatchScheduler
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
}
