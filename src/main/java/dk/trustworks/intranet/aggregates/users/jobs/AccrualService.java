package dk.trustworks.intranet.aggregates.users.jobs;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.Vacation;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.VacationType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class AccrualService {

    private static final double VACATION_DAYS_PER_MONTH = 2.5;

    @Inject
    UserService userService;

    @Transactional
    @Scheduled(cron = "0 0 0 1 * ?")
    public void accrueVacationDays() {
        log.info("Starting monthly vacation accrual process...");
        LocalDate currentDate = LocalDate.now();
        List<User> currentlyEmployedUsers = userService.findCurrentlyEmployedUsers(true, ConsultantType.CONSULTANT, ConsultantType.STAFF);
        currentlyEmployedUsers.forEach(user -> {
            log.infof("Adding %f vacation days to pool %s (User : %s)", VACATION_DAYS_PER_MONTH, user.getFullname());
            new Vacation(UUID.randomUUID().toString(), user.getUuid(), VacationType.REGULAR, currentDate, VACATION_DAYS_PER_MONTH).persist();
        });
        log.info("Monthly vacation accrual process completed.");
    }
}