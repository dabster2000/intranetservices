package dk.trustworks.intranet.aggregates.users.jobs;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.Vacation;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.VacationType;
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
    //@Scheduled(cron = "0 0 0 1 * ?")
    public void accrueVacationDays() {
        log.info("Starting monthly vacation accrual process...");
        LocalDate currentDate = LocalDate.now();
        List<User> currentlyEmployedUsers = userService.findCurrentlyEmployedUsers(true, ConsultantType.CONSULTANT, ConsultantType.STAFF);

        currentlyEmployedUsers.forEach(user -> {
            log.infof("Adding %.1f vacation days to pool for user: %s (UUID: %s)",
                    VACATION_DAYS_PER_MONTH, user.getFullname(), user.getUuid());
            new Vacation(UUID.randomUUID().toString(), user.getUuid(), VacationType.REGULAR, currentDate, VACATION_DAYS_PER_MONTH).persist();
        });

        log.info("Monthly vacation accrual process completed.");
    }

    @Transactional
    //@Scheduled(every = "24h", delay = 1)
    public void initVacation() {
        log.info("Starting vacation initialization process...");
        LocalDate endDate = LocalDate.of(2024, 11, 1);
        List<User> currentlyEmployedUsers = userService.findCurrentlyEmployedUsers(false, ConsultantType.CONSULTANT, ConsultantType.STAFF);

        currentlyEmployedUsers.forEach(user -> {
            LocalDate startDate = user.getHireDate().withDayOfMonth(1);
            log.infof("Initializing vacation accrual for user: %s (UUID: %s) from %s to %s",
                    user.getFullname(), user.getUuid(), startDate.withDayOfMonth(1), endDate);

            do {
                log.infof("Adding %.1f vacation days to pool for user: %s (UUID: %s) on %s",
                        VACATION_DAYS_PER_MONTH, user.getFullname(), user.getUuid(), startDate);
                new Vacation(UUID.randomUUID().toString(), user.getUuid(), VacationType.REGULAR, startDate, VACATION_DAYS_PER_MONTH).persist();
                startDate = startDate.plusMonths(1);
            } while (!startDate.isAfter(endDate));
        });

        log.info("Vacation initialization process completed.");
    }
}