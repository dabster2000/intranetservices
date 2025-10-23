package dk.trustworks.intranet.bi.services;


import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@JBossLog
@ApplicationScoped
public class UserSalaryCalculatorService {

    @Inject
    SalaryService salaryService;
    @Inject
    BiDataPerDayRepository biDataRepository;
    @Inject
    UserService userService;

    @Transactional
    public void recalculateSalary(String useruuid, LocalDate testDay) {

        int sal = salaryService.getUserSalaryByMonth(useruuid, testDay).getSalary(); // uses DB order/filter

        StatusType userStatus = userService.findUserStatuses(useruuid).stream()
                .sorted(Comparator.comparing(UserStatus::getStatusdate).reversed())
                .filter(s -> !s.getStatusdate().isAfter(testDay))
                .map(UserStatus::getStatus)
                .findFirst()
                .orElse(StatusType.TERMINATED);

        if (userStatus == StatusType.TERMINATED ||
                userStatus == StatusType.PREBOARDING ||
                userStatus == StatusType.NON_PAY_LEAVE) {
            sal = 0;
        }

        updateSalary(useruuid, testDay, sal);
    }

    private void updateSalary(String useruuid, LocalDate activeFrom, int salary) {
        biDataRepository.insertOrUpdateSalary(useruuid, activeFrom, activeFrom.getYear(), activeFrom.getMonthValue(), activeFrom.getDayOfMonth(), salary);
    }
}
