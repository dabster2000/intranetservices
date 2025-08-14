package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.bidata.repositories.BiDataPerDayRepository;
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

        List<WorkFull> workList = WorkFull.list("useruuid = ?1 and registered = ?2", user.getUuid(), testDay);

        EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
        update(document);
    }

    private void update(EmployeeAvailabilityPerDayAggregate document) {
        if (document.getCompany() == null) {
            log.warnf("Skipping availability write due to null company user=%s date=%s", document.getUser().getUuid(), document.getDocumentDate());
            return;
        }
        //QuarkusTransaction.requiringNew().run(() ->
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
                document.isTwBonusEligible());
        //);
    }

    private EmployeeAvailabilityPerDayAggregate createAvailabilityDocumentByUserAndDate(User user, LocalDate testDay, List<WorkFull> workByDay) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);
        if (userStatus == null) {
            log.warnf("No UserStatus found for user=%s on %s; treating as zero availability", user.getUuid(), testDay);
        }

        int weeklyAllocation = (userStatus != null ? userStatus.getAllocation() : 0); // fx 37 timer

        double fullAvailability = weeklyAllocation / 5.0; // 7.4
        if (DateUtils.isWeekend(testDay)) fullAvailability = 0.0;

        double nonPaidLeaveHoursPerDay = (userStatus != null && userStatus.getStatus().equals(NON_PAY_LEAVE)) ? fullAvailability : 0.0;
        double paidLeaveHoursPerDay = (userStatus != null && userStatus.getStatus().equals(PAID_LEAVE)) ? fullAvailability : 0.0;
        double maternityLeaveHoursPerDay = (userStatus != null && userStatus.getStatus().equals(MATERNITY_LEAVE)) ? fullAvailability : 0.0;

        double vacationHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> VACATION.equals(w.getTaskuuid())).mapToDouble(WorkFull::getWorkduration).sum());
        double sicknessHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> SICKNESS.equals(w.getTaskuuid())).mapToDouble(WorkFull::getWorkduration).sum());
        String matTask = MATERNITY_LEAVE.getTaskuuid();
        double registeredMatLeave = workByDay.stream()
                .filter(w -> matTask != null && matTask.equals(w.getTaskuuid()))
                .mapToDouble(WorkFull::getWorkduration)
                .sum();
        maternityLeaveHoursPerDay = Math.min(fullAvailability, maternityLeaveHoursPerDay + registeredMatLeave);

        double unavailableHours = (DateUtils.isFriday(testDay)) ? Math.min(2.0, fullAvailability) : 0.0;
        unavailableHours = DateUtils.isFirstThursdayOrFridayInOctober(testDay) ? Math.min(7.4, fullAvailability) : unavailableHours;

        return new EmployeeAvailabilityPerDayAggregate(
                userStatus != null ? userStatus.getCompany() : null,
                testDay,
                user,
                fullAvailability,
                unavailableHours,
                vacationHoursPerDay,
                sicknessHoursPerDay,
                maternityLeaveHoursPerDay,
                nonPaidLeaveHoursPerDay,
                paidLeaveHoursPerDay,
                userStatus != null ? userStatus.getType() : null,
                userStatus != null ? userStatus.getStatus() : null,
                0,
                userStatus != null && userStatus.isTwBonusEligible()
        );
    }

}
