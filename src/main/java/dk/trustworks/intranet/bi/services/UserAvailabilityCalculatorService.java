package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;

@JBossLog
@ApplicationScoped
public class UserAvailabilityCalculatorService {

    @Inject
    UserService userService;

    @Inject
    BiDataPerDayRepository biDataRepository;

    /**
     * This method is invoked by the reactive processor for each day’s update event.
     * The workList parameter is provided from the shared AvailabilityData.
     */
    public void updateUserAvailabilityByDay(String useruuid, LocalDate testDay, List<WorkFull> workList) {
        User user = User.findById(useruuid);
        EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
        update(document);
    }

    private void update(EmployeeAvailabilityPerDayAggregate document) {
        QuarkusTransaction.requiringNew().run(() ->
            biDataRepository.insertOrUpdateData(
                document.getUser().getUuid(),
                document.getDocumentDate().toString(),
                document.getDocumentDate().getYear(),
                document.getDocumentDate().getMonthValue(),
                document.getDocumentDate().getDayOfMonth(),
                document.getCompany().getUuid(),
                document.getGrossAvailableHours(),
                document.getUnavavailableHours(),
                document.getVacationHours(),
                document.getSickHours(),
                document.getMaternityLeaveHours(),
                document.getNonPaydLeaveHours(),
                document.getPaidLeaveHours(),
                document.getConsultantType().name(),
                document.getStatusType().name(),
                document.isTwBonusEligible()
            )
        );
    }

    private EmployeeAvailabilityPerDayAggregate createAvailabilityDocumentByUserAndDate(User user, LocalDate testDay, List<WorkFull> workList) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);

        int weeklyAllocation = userStatus.getAllocation(); // e.g., 37 hours
        double fullAvailability = weeklyAllocation / 5.0; // e.g., ~7.4 hours per day
        if (DateUtils.isWeekendDay(testDay)) fullAvailability = 0.0;

        List<WorkFull> workByDay = workList.stream()
            .filter(w -> w.getRegistered().isEqual(testDay) && w.getUseruuid().equals(user.getUuid()))
            .toList();

        double nonPaydLeaveHoursPerday = userStatus.getStatus().equals(NON_PAY_LEAVE) ? fullAvailability : 0.0;
        double paidLeaveHoursPerDay = userStatus.getStatus().equals(PAID_LEAVE) ? fullAvailability : 0.0;
        double maternityLeaveHoursPerDay = userStatus.getStatus().equals(MATERNITY_LEAVE) ? fullAvailability : 0.0;

        double vacationHoursPerDay = Math.min(fullAvailability,
            workByDay.stream().filter(w -> w.getTaskuuid().equals("VACATION")).mapToDouble(WorkFull::getWorkduration).sum());
        double sicknessHoursPerDay = Math.min(fullAvailability,
            workByDay.stream().filter(w -> w.getTaskuuid().equals("SICKNESS")).mapToDouble(WorkFull::getWorkduration).sum());
        maternityLeaveHoursPerDay = Math.min(fullAvailability,
            maternityLeaveHoursPerDay + workByDay.stream().filter(w -> w.getTaskuuid().equals("MATERNITY_LEAVE")).mapToDouble(WorkFull::getWorkduration).sum());

        double unavailableHours = DateUtils.isFriday(testDay) ? Math.min(2.0, fullAvailability) : 0.0;
        unavailableHours = DateUtils.isFirstThursdayOrFridayInOctober(testDay) ? Math.min(7.4, fullAvailability) : unavailableHours;

        return new EmployeeAvailabilityPerDayAggregate(
            userStatus.getCompany(), testDay, user, fullAvailability, unavailableHours,
            vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay,
            nonPaydLeaveHoursPerday, paidLeaveHoursPerDay,
            userStatus.getType(), userStatus.getStatus(), 0, userStatus.isTwBonusEligible()
        );
    }
}