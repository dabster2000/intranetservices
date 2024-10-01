package dk.trustworks.intranet.aggregates.bidata.services;

import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    public void recalculateSalary(String useruuid) {
        log.info("Recalculate salary for " + useruuid);
        List<UserStatus> userStatusList = userService.findUserStatuses(useruuid);
        List<Salary> salaryList = salaryService.findByUseruuid(useruuid);

        //QuarkusTransaction.requiringNew().run(() -> {
            LocalDate startDate = DateUtils.getCompanyStartDate();
            LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(1);
            do {
                LocalDate finalStartDate = startDate;

                Salary sal = salaryList.stream()
                        .sorted(Comparator.comparing(Salary::getActivefrom).reversed())
                        .filter(s -> s.getActivefrom().isBefore(finalStartDate) || s.getActivefrom().isEqual(finalStartDate))
                        .findFirst()
                        .orElse(new Salary(DateUtils.getCurrentFiscalStartDate().plusYears(1), 0, useruuid));

                StatusType userStatus = userStatusList.stream()
                        .sorted(Comparator.comparing(UserStatus::getStatusdate).reversed())
                        .filter(s -> s.getStatusdate().isBefore(finalStartDate) || s.getStatusdate().isEqual(finalStartDate))
                        .map(UserStatus::getStatus)
                        .findFirst()
                        .orElse(StatusType.TERMINATED);
                if(startDate.getDayOfMonth() == 1) {
                    log.info("User: " + useruuid + " - " + startDate + " - " + userStatus + " - " + sal.getSalary());
                }
                if(userStatus.equals(StatusType.TERMINATED) || userStatus.equals(StatusType.PREBOARDING) || userStatus.equals(StatusType.NON_PAY_LEAVE)) sal.setSalary(0);
                if(startDate.getDayOfMonth() == 1) {
                    log.info("User: " + useruuid + " - " + startDate + " - " + userStatus + " - " + sal.getSalary() + " - [ADJUSTED]");
                }

                //sal.setActivefrom(startDate);
                QuarkusTransaction.requiringNew().run(() -> updateSalary(useruuid, finalStartDate, sal.getSalary()));

                startDate = startDate.plusDays(1);
            } while (startDate.isBefore(endDate));
        //});
        log.info("Recalculate salary for " + useruuid + " done");
    }

    private void updateSalary(String useruuid, LocalDate activeFrom, int salary) {
        biDataRepository.insertOrUpdateSalary(useruuid, activeFrom, activeFrom.getYear(), activeFrom.getMonthValue(), activeFrom.getDayOfMonth(), salary);
    }
}
