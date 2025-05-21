package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.bi.events.SalaryChangedDayEvent;
import dk.trustworks.intranet.bi.events.SalaryData;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class SalaryCalculationService {

    private static final Logger LOG = Logger.getLogger(SalaryCalculationService.class);

    @Inject
    SalaryService salaryService;

    @Inject
    UserService userService;

    @Inject
    BiDataPerDayRepository biDataRepository;

    @Transactional
    public void recalculateSalary(SalaryChangedDayEvent event) {
        String useruuid = event.useruuid();
        LocalDate testDay = event.testDay();
        // Use the pre-fetched data from the event
        SalaryData salaryData = event.salaryData();
        List<UserStatus> userStatusList = salaryData.userStatusList();
        List<Salary> salaryList = salaryData.salaryList();

        //LOG.infof("Recalculating salary for user %s on day %s", useruuid, testDay);

        Salary sal = salaryList.stream()
                .sorted(Comparator.comparing(Salary::getActivefrom).reversed())
                .filter(s -> !s.getActivefrom().isAfter(testDay))
                .findFirst()
                .orElse(new Salary(DateUtils.getCurrentFiscalStartDate().plusYears(1), 0, useruuid));

        StatusType userStatus = userStatusList.stream()
                .sorted(Comparator.comparing(UserStatus::getStatusdate).reversed())
                .filter(s -> !s.getStatusdate().isAfter(testDay))
                .map(UserStatus::getStatus)
                .findFirst()
                .orElse(StatusType.TERMINATED);

        if (userStatus.equals(StatusType.TERMINATED) ||
                userStatus.equals(StatusType.PREBOARDING) ||
                userStatus.equals(StatusType.NON_PAY_LEAVE)) {
            sal.setSalary(0);
        }

        // Use a new transaction for each update to keep transactions short.
        //QuarkusTransaction.requiringNew().run(() -> updateSalary(useruuid, testDay, sal.getSalary()));
        updateSalary(useruuid, testDay, sal.getSalary());
    }

    private void updateSalary(String useruuid, LocalDate activeFrom, int salary) {
        biDataRepository.insertOrUpdateSalary(
                useruuid,
                activeFrom,
                activeFrom.getYear(),
                activeFrom.getMonthValue(),
                activeFrom.getDayOfMonth(),
                salary
        );
    }
}