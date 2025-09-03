package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;


@ApplicationScoped
public class AvailabilityCalculatingExecutor {

    @Inject
    UserService userService;
    @Inject
    WorkService workService;

    public void createAvailabilityDocumentByUserAndDay(String useruuid, LocalDate testDay) {
        User user = userService.findById(useruuid, false);
        if(user==null) return;
        List<WorkFull> workList = workService.findByPeriodAndUserUUID(testDay, testDay.plusDays(1), user.getUuid());

        QuarkusTransaction.begin();
        EmployeeAvailabilityPerDayAggregate.delete("user = ?1 and documentDate = ?2", user, testDay);
        QuarkusTransaction.commit();

        try {
            QuarkusTransaction.begin();
            EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDay(userService.findById(user.getUuid(), false), testDay, workList);
            EmployeeAvailabilityPerDayAggregate.persist(document);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private EmployeeAvailabilityPerDayAggregate createAvailabilityDocumentByUserAndDay(User user, LocalDate testDay, List<WorkFull> workList) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);

        int weeklyAllocation = userStatus.getAllocation(); // fx 37 timer

        double fullAvailability = weeklyAllocation / 5.0; // 7.4
        if (DateUtils.isWeekend(testDay)) fullAvailability = 0.0;

        List<WorkFull> workByDay = workList.stream().filter(w -> w.getRegistered().isEqual(testDay) && w.getUseruuid().equals(user.getUuid())).toList();

        double nonPaydLeaveHoursPerday = userStatus.getStatus().equals(NON_PAY_LEAVE) ? fullAvailability : 0.0;
        double paidLeaveHoursPerDay = userStatus.getStatus().equals(PAID_LEAVE) ? fullAvailability : 0.0;
        double maternityLeaveHoursPerDay = userStatus.getStatus().equals(MATERNITY_LEAVE) ? fullAvailability : 0.0;

        double vacationHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(VACATION)).mapToDouble(WorkFull::getWorkduration).sum());
        double sicknessHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(SICKNESS)).mapToDouble(WorkFull::getWorkduration).sum());
        maternityLeaveHoursPerDay = Math.min(fullAvailability, maternityLeaveHoursPerDay + workByDay.stream().filter(w -> w.getTaskuuid().equals(MATERNITY_LEAVE.getTaskuuid())).mapToDouble(WorkFull::getWorkduration).sum());

        double unavailableHours = (DateUtils.isFriday(testDay))?Math.min(2.0,fullAvailability):0.0;
        unavailableHours = DateUtils.isFirstThursdayOrFridayInOctober(testDay)?Math.min(7.4,fullAvailability):unavailableHours;

        return new EmployeeAvailabilityPerDayAggregate(userStatus.getCompany(), testDay, user, fullAvailability, unavailableHours, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, userStatus.getType(), userStatus.getStatus(), 0, userStatus.isTwBonusEligible());
    }

}
