package dk.trustworks.intranet.aggregates.bidata.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;

@JBossLog
@ApplicationScoped
public class UserAvailabilityCalculatorService {

    @Inject
    WorkService workService;

    @Inject
    UserService userService;

    @Inject
    EntityManager em;

    @Inject
    BiDataPerDayRepository biDataRepository;
    @Inject
    BudgetService budgetService;

    public void updateUserAvailability(String useruuid) {
        log.info("Recalculate availability for " + useruuid);

        LocalDate startDate = DateUtils.getCompanyStartDate();
        LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(1);

        int day = 0;

        User user = userService.findById(useruuid, false);
        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);

        List<EmployeeAvailabilityPerDayAggregate> result = new ArrayList<>();
        do {
            LocalDate testDay = startDate.plusDays(day);
            EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
            result.add(document);
            day++;
        } while (startDate.plusDays(day).isBefore(endDate));
        QuarkusTransaction.requiringNew().run(() -> {
            for (EmployeeAvailabilityPerDayAggregate document : result) {
                update(document);
            }
        });
        log.info("AvailabilityCalculatingExecutor.process - Done for period " + startDate + " - " + endDate);
    }

    @Transactional
    public void updateUserAvailabilityByDay(String useruuid, LocalDate testDay) {
        log.info("Recalculate availability for " + useruuid + " on " + testDay);

        User user = userService.findById(useruuid, false);
        List<WorkFull> workList = workService.findByPeriod(testDay, testDay.plusDays(1));

        EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
        update(document);

        log.info("AvailabilityCalculatingExecutor.process - Done for day " + testDay);
    }

    private void update(EmployeeAvailabilityPerDayAggregate document) {
        biDataRepository.insertOrUpdateData(document.getUser().getUuid(), document.getDocumentDate().toString(), document.getDocumentDate().getYear(), document.getDocumentDate().getMonthValue(), document.getDocumentDate().getDayOfMonth(), document.getCompany().getUuid(), document.getGrossAvailableHours(), document.getUnavavailableHours(), document.getVacationHours(), document.getSickHours(), document.getMaternityLeaveHours(), document.getNonPaydLeaveHours(), document.getPaidLeaveHours(), document.getConsultantType().name(), document.getStatusType().name(), document.isTwBonusEligible());
    }

    private EmployeeAvailabilityPerDayAggregate createAvailabilityDocumentByUserAndDate(User user, LocalDate testDay, List<WorkFull> workList) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);

        int weeklyAllocation = userStatus.getAllocation(); // fx 37 timer

        double fullAvailability = weeklyAllocation / 5.0; // 7.4
        if (!DateUtils.isWorkday(testDay)) fullAvailability = 0.0;

        List<WorkFull> workByDay = workList.stream().filter(w -> w.getRegistered().isEqual(testDay) && w.getUseruuid().equals(user.getUuid())).toList();

        double nonPaydLeaveHoursPerday = userStatus.getStatus().equals(NON_PAY_LEAVE) ? fullAvailability : 0.0;
        double paidLeaveHoursPerDay = userStatus.getStatus().equals(PAID_LEAVE) ? fullAvailability : 0.0;
        double maternityLeaveHoursPerDay = userStatus.getStatus().equals(MATERNITY_LEAVE) ? fullAvailability : 0.0;

        double vacationHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(VACATION)).mapToDouble(WorkFull::getWorkduration).sum());
        double sicknessHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(SICKNESS)).mapToDouble(WorkFull::getWorkduration).sum());
        maternityLeaveHoursPerDay = Math.min(fullAvailability, maternityLeaveHoursPerDay + workByDay.stream().filter(w -> w.getTaskuuid().equals(MATERNITY_LEAVE.getTaskuuid())).mapToDouble(WorkFull::getWorkduration).sum());

        double unavailableHours = (DateUtils.isFriday(testDay))?Math.min(2.0,fullAvailability):0.0;

        return new EmployeeAvailabilityPerDayAggregate(userStatus.getCompany(), testDay, user, fullAvailability, unavailableHours, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, userStatus.getType(), userStatus.getStatus(), 0, userStatus.isTwBonusEligible());
    }

}
