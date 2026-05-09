package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;


@JBossLog
@ApplicationScoped
public class UserAvailabilityCalculatorService {

    @Inject
    UserService userService;

    @Inject
    BiDataPerDayRepository biDataRepository;

    @Transactional
    public void updateUserAvailabilityByDay(String useruuid, LocalDate testDay) {
        if (useruuid == null || testDay == null) {
            log.warnf("updateUserAvailabilityByDay called with nulls user=%s date=%s", useruuid, testDay);
            return;
        }
        User user = User.findById(useruuid);
        if (user == null) {
            log.warnf("User not found user=%s date=%s; skipping availability update", useruuid, testDay);
            return;
        }
        // Actively load user statuses into the transient collection to enable getUserStatus()
        user.setStatuses(userService.findUserStatuses(useruuid));

        UserStatus userStatus = userService.getUserStatus(user, testDay);
        if (userStatus == null) {
            log.warnf("No UserStatus found for user=%s on %s; treating as zero availability", user.getUuid(), testDay);
        }
        if (userStatus == null || userStatus.getCompany() == null) {
            log.warnf("Skipping availability write due to null company user=%s date=%s", user.getUuid(), testDay);
            return;
        }

        List<WorkFull> workList = WorkFull.list("useruuid = ?1 and registered = ?2", user.getUuid(), testDay);

        int weeklyAllocation = userStatus.getAllocation();
        double fullAvailability = weeklyAllocation / 5.0;
        if (DateUtils.isWeekend(testDay)) fullAvailability = 0.0;

        double nonPaidLeaveHours = userStatus.getStatus().equals(NON_PAY_LEAVE) ? fullAvailability : 0.0;
        double paidLeaveHours = userStatus.getStatus().equals(PAID_LEAVE) ? fullAvailability : 0.0;
        double maternityStatusHours = userStatus.getStatus().equals(MATERNITY_LEAVE) ? fullAvailability : 0.0;

        double vacationHours = Math.min(fullAvailability, workList.stream()
                .filter(w -> VACATION.equals(w.getTaskuuid()))
                .mapToDouble(WorkFull::getWorkduration).sum());
        double sicknessHours = Math.min(fullAvailability, workList.stream()
                .filter(w -> SICKNESS.equals(w.getTaskuuid()))
                .mapToDouble(WorkFull::getWorkduration).sum());
        String matTask = MATERNITY_LEAVE.getTaskuuid();
        double registeredMatLeave = workList.stream()
                .filter(w -> matTask != null && matTask.equals(w.getTaskuuid()))
                .mapToDouble(WorkFull::getWorkduration).sum();
        double maternityLeaveHours = Math.min(fullAvailability, maternityStatusHours + registeredMatLeave);

        double unavailableHours = DateUtils.isFriday(testDay) ? Math.min(2.0, fullAvailability) : 0.0;
        unavailableHours = DateUtils.isFirstThursdayOrFridayInOctober(testDay)
                ? Math.min(7.4, fullAvailability)
                : unavailableHours;

        biDataRepository.insertOrUpdateData(
                user.getUuid(),
                testDay.toString(),
                testDay.getYear(),
                testDay.getMonthValue(),
                testDay.getDayOfMonth(),
                userStatus.getCompany().getUuid(),
                BigDecimal.valueOf(fullAvailability),
                BigDecimal.valueOf(unavailableHours),
                BigDecimal.valueOf(vacationHours),
                BigDecimal.valueOf(sicknessHours),
                BigDecimal.valueOf(maternityLeaveHours),
                BigDecimal.valueOf(nonPaidLeaveHours),
                BigDecimal.valueOf(paidLeaveHours),
                userStatus.getType().name(),
                userStatus.getStatus().name(),
                userStatus.isTwBonusEligible());
    }
}
