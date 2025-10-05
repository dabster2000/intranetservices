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
        log.trace("Recalculate salary for " + useruuid + " on " + testDay + " started");
        List<UserStatus> userStatusList = userService.findUserStatuses(useruuid);
        List<Salary> salaryList = salaryService.findByUseruuid(useruuid);

        //LocalDate startDate = DateUtils.getCompanyStartDate();
        //LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(1);
        //do {

        Salary sal = salaryList.stream()
                    .sorted(Comparator.comparing(Salary::getActivefrom).reversed())
                    .filter(s -> s.getActivefrom().isBefore(testDay) || s.getActivefrom().isEqual(testDay))
                    .findFirst()
                    .orElse(new Salary(DateUtils.getCurrentFiscalStartDate().plusYears(1), 0, useruuid));

            StatusType userStatus = userStatusList.stream()
                    .sorted(Comparator.comparing(UserStatus::getStatusdate).reversed())
                    .filter(s -> s.getStatusdate().isBefore(testDay) || s.getStatusdate().isEqual(testDay))
                    .map(UserStatus::getStatus)
                    .findFirst()
                    .orElse(StatusType.TERMINATED);
            /*
            if(startDate.getDayOfMonth() == 1) {
                log.info("User: " + useruuid + " - " + startDate + " - " + userStatus + " - " + sal.getSalary());
            }
             */
            if(userStatus.equals(StatusType.TERMINATED) || userStatus.equals(StatusType.PREBOARDING) || userStatus.equals(StatusType.NON_PAY_LEAVE)) sal.setSalary(0);
            /*
            if(startDate.getDayOfMonth() == 1) {
                log.info("User: " + useruuid + " - " + startDate + " - " + userStatus + " - " + sal.getSalary() + " - [ADJUSTED]");
            }

             */

            updateSalary(useruuid, testDay, sal.getSalary());

        //    startDate = startDate.plusDays(1);
        //} while (startDate.isBefore(endDate));
        log.trace("Recalculate salary for " + useruuid + " done");
    }

    private void updateSalary(String useruuid, LocalDate activeFrom, int salary) {
        biDataRepository.insertOrUpdateSalary(useruuid, activeFrom, activeFrom.getYear(), activeFrom.getMonthValue(), activeFrom.getDayOfMonth(), salary);
    }
}
