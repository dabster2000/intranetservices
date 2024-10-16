package dk.trustworks.intranet.aggregates.availability.jobs;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.messaging.emitters.MessageEmitter.YEAR_CHANGE_EVENT;
import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.EXTERNAL;
import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.STUDENT;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;

@JBossLog
@ApplicationScoped
public class AvailabilityCalculatingExecutor {

    private List<User> employedUsers = new ArrayList<>();
    @Inject
    UserService userService;
    @Inject
    WorkService workService;

    //@ConsumeEvent(value = YEAR_CHANGE_EVENT, blocking = true)
    public void process(DateRangeMap dateRangeMap) {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();

        log.info("AvailabilityCalculatingExecutor.process - Start for period" + startDate + " - " + endDate);

        int day = 0;

        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);
        QuarkusTransaction.begin();
        EmployeeAvailabilityPerDayAggregate.delete("documentDate >= ?1 and documentDate < ?2", startDate, endDate);
        QuarkusTransaction.commit();

        try {
            ArrayList<EmployeeAvailabilityPerDayAggregate> list = new ArrayList<>();
            do {
                LocalDate testDay = startDate.plusDays(day);
                for (User user : employedUsers) {
                    EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
                    list.add(document);
                }
                day++;
            } while (startDate.plusDays(day).isBefore(endDate));
            QuarkusTransaction.begin();
            EmployeeAvailabilityPerDayAggregate.persist(list);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("AvailabilityCalculatingExecutor.process - Done for period " + startDate + " - " + endDate);
    }

    //@ConsumeEvent(value = USER_EVENT, blocking = true)
    public void createAvailabilityDocumentByUser(AggregateRootChangeEvent event) {
        log.info("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUser -> event = " + event);
        User user = userService.findById(event.getAggregateRootUUID(), false);
        if(user==null) return;
        LocalDate year = DateUtils.getCompanyStartDate();
        LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(3);
        int day = 0;

        List<WorkFull> workList = workService.findByPeriodAndUserUUID(year, endDate, user.getUuid());

        QuarkusTransaction.begin();
        EmployeeAvailabilityPerDayAggregate.delete("user = ?1", user);
        QuarkusTransaction.commit();
        try {
            ArrayList<EmployeeAvailabilityPerDayAggregate> list = new ArrayList<>();
            do {
                LocalDate testDay = year.plusDays(day);
                EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
                list.add(document);
                day++;
            } while (year.plusDays(day).isBefore(endDate));
            QuarkusTransaction.begin();
            EmployeeAvailabilityPerDayAggregate.persist(list);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            log.error(e);
        }
    }

    //@ConsumeEvent(value = WORK_UPDATE_EVENT, blocking = true)
    public void createAvailabilityDocumentByUserAndDate(String useruuid, LocalDate testDay) {
        log.info("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUserAndDate");
        User user = userService.findById(useruuid, false);
        if(user==null) return;
        List<WorkFull> workList = workService.findByPeriodAndUserUUID(testDay, testDay.plusDays(1), user.getUuid());

        QuarkusTransaction.begin();
        EmployeeAvailabilityPerDayAggregate.delete("user = ?1 and documentDate = ?2", user, testDay);
        QuarkusTransaction.commit();

        try {
            QuarkusTransaction.begin();
            EmployeeAvailabilityPerDayAggregate document = createAvailabilityDocumentByUserAndDate(userService.findById(user.getUuid(), false), testDay, workList);
            EmployeeAvailabilityPerDayAggregate.persist(document);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private EmployeeAvailabilityPerDayAggregate createAvailabilityDocumentByUserAndDate(User user, LocalDate testDay, List<WorkFull> workList) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);
        int userSalary = user.getSalary(testDay).getSalary();//userService.getUserSalary(user, testDay).getSalary();

        if(userStatus.getType().equals(EXTERNAL)) userSalary = 0;
        if(userStatus.getType().equals(STUDENT)) userSalary = userSalary * 20 * 4;
        if(userStatus.getStatus().equals(NON_PAY_LEAVE) || userStatus.getStatus().equals(TERMINATED)) userSalary = 0;

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

        //double registeredBillableHours = workByDay.stream().filter(w -> w.getRate() > 0 && w.getWorkas() == null).mapToDouble(WorkFull::getWorkduration).sum();
        //double helpedColleagueBillableHours = workByDay.stream().filter(w -> w.getRate() > 0 && w.getWorkas() != null).mapToDouble(WorkFull::getWorkduration).sum();
        //double registeredAmount = workByDay.stream().filter(w -> w.getRate() > 0).mapToDouble(workFull -> workFull.getWorkduration() * workFull.getRate()).sum();

        double unavailableHours = (DateUtils.isFriday(testDay))?Math.min(2.0,fullAvailability):0.0;

        return new EmployeeAvailabilityPerDayAggregate(userStatus.getCompany(), testDay, user, fullAvailability, unavailableHours, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, userStatus.getType(), userStatus.getStatus(), userSalary, userStatus.isTwBonusEligible());
    }

    @Inject
    EventBus eventBus;

    /**
     * This method is called every night at 1:00
     */
    //@Transactional
    //@Scheduled(every = "1m")
    //@Scheduled(cron = "0 0 1 * * ?")
    public void recalculateAvailability() {
        employedUsers = userService.listAll(false);
        LocalDate testDate = DateUtils.getCompanyStartDate();
        do {
            if(EmployeeAvailabilityPerDayAggregate.find("year = ?1 and month = ?2", testDate.getYear(), testDate.getMonthValue()).count()==0) {
                eventBus.publish(YEAR_CHANGE_EVENT, new DateRangeMap(testDate, testDate.plusMonths(1)));
                return;
            }
            testDate = testDate.plusMonths(1);
        } while (testDate.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(3)));

        EmployeeAvailabilityPerDayAggregate.find("lastUpdate < ?1", LocalDateTime.now().minusDays(1)).firstResultOptional().ifPresent(employeeDataPerDay -> {
            LocalDate lastUpdate = ((EmployeeAvailabilityPerDayAggregate) employeeDataPerDay).getDocumentDate();
            eventBus.publish(YEAR_CHANGE_EVENT, new DateRangeMap(lastUpdate, lastUpdate.plusMonths(1)));
        });
    }

    //@Transactional
    //@Scheduled(cron = "0 0 0 * * ?")
    public void recalculateElegibility() {
        employedUsers = userService.listAll(false); //findEmployedUsersByDate(testDate, false, ConsultantType.CONSULTANT);
        LocalDate testFiscalYear = DateUtils.getCurrentFiscalStartDate();
        List<EmployeeBonusEligibility> employeeBonusEligibilityList = EmployeeBonusEligibility.find("year = ?1", testFiscalYear.getYear()).list();

        employedUsers.forEach(user -> {
            if(employeeBonusEligibilityList.stream().noneMatch(e -> e.getUser().getUuid().equals(user.getUuid()))) {
                Stream<EmployeeAvailabilityPerDayAggregate> stream = EmployeeAvailabilityPerDayAggregate.stream("((year = ?1 AND month >= ?2) OR (year = ?3 AND month <= ?4)) and user = ?5", testFiscalYear.getYear(), testFiscalYear.getMonthValue(), testFiscalYear.plusYears(1).getYear(), testFiscalYear.plusYears(1).getMonthValue(), user);
                double sum = stream.mapToDouble(EmployeeAvailabilityPerDayAggregate::getSalary).sum();
                if(sum >0) {
                    EmployeeBonusEligibility.persist(new EmployeeBonusEligibility(user, testFiscalYear.getYear(), true, false, false, false, false, false, false, false, false, false, false, false, false));
                }
            }
        });
    }
}
