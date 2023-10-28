package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.sender.SystemChangeEvent;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.EmployeeDataPerMonth;
import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.messaging.dto.UserDateMap;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import dk.trustworks.intranet.model.EmployeeDataPerDay;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.*;
import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.USER_EVENT;
import static dk.trustworks.intranet.messaging.emitters.MessageEmitter.YEAR_CHANGE_EVENT;
import static dk.trustworks.intranet.messaging.emitters.SystemMessageEmitter.WORK_UPDATE_EVENT;
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

    @ConsumeEvent(value = YEAR_CHANGE_EVENT, blocking = true)
    public void process(DateRangeMap dateRangeMap) throws Exception {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();

        log.info("AvailabilityCalculatingExecutor.process - Start for period" + startDate + " - " + endDate);

        int day = 0;

        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);
        QuarkusTransaction.begin();
        EmployeeDataPerDay.delete("documentDate >= ?1 and documentDate < ?2", startDate, endDate);
        QuarkusTransaction.commit();

        try {
            ArrayList<EmployeeDataPerDay> list = new ArrayList<>();
            do {
                LocalDate testDay = startDate.plusDays(day);
                for (User user : employedUsers) {
                    EmployeeDataPerDay document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
                    list.add(document);
                }
                day++;
            } while (startDate.plusDays(day).isBefore(endDate));
            QuarkusTransaction.begin();
            EmployeeDataPerDay.persist(list);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("AvailabilityCalculatingExecutor.process - Done for period " + startDate + " - " + endDate);
    }

    @ConsumeEvent(value = USER_EVENT, blocking = true)
    public void createAvailabilityDocumentByUser(AggregateRootChangeEvent event) {
        log.info("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUser -> event = " + event);
        User user = userService.findById(event.getAggregateRootUUID(), false);
        if(user==null) return;
        LocalDate year = DateUtils.getCompanyStartDate();
        LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(3);
        int day = 0;

        List<WorkFull> workList = workService.findByPeriodAndUserUUID(year, endDate, user.getUuid());

        QuarkusTransaction.begin();
        EmployeeDataPerDay.delete("user = ?1", user);
        QuarkusTransaction.commit();
        try {
            ArrayList<EmployeeDataPerDay> list = new ArrayList<>();
            do {
                LocalDate testDay = year.plusDays(day);
                EmployeeDataPerDay document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
                list.add(document);
                day++;
            } while (year.plusDays(day).isBefore(endDate));
            QuarkusTransaction.begin();
            EmployeeDataPerDay.persist(list);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            log.error(e);
        }
    }

    @ConsumeEvent(value = WORK_UPDATE_EVENT, blocking = true)
    public void createAvailabilityDocumentByUserAndDate(SystemChangeEvent event) {
        log.info("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUserAndDate");
        UserDateMap userDateMap = new JsonObject(event.getEventContent()).mapTo(UserDateMap.class);
        String useruuid = userDateMap.getUseruuid();
        LocalDate testDay = userDateMap.getDate();
        User user = userService.findById(useruuid, false);
        if(user==null) return;
        List<WorkFull> workList = workService.findByPeriodAndUserUUID(testDay, testDay.plusDays(1), user.getUuid());

        QuarkusTransaction.begin();
        EmployeeDataPerDay.delete("user = ?1 and documentDate = ?2", user, testDay);
        QuarkusTransaction.commit();

        try {
            QuarkusTransaction.begin();
            EmployeeDataPerDay document = createAvailabilityDocumentByUserAndDate(userService.findById(user.getUuid(), false), testDay, workList);
            EmployeeDataPerDay.persist(document);
            QuarkusTransaction.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private EmployeeDataPerDay createAvailabilityDocumentByUserAndDate(User user, LocalDate testDay, List<WorkFull> workList) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);
        int userSalary = user.getSalary(testDay).getSalary();//userService.getUserSalary(user, testDay).getSalary();

        if(userStatus.getType().equals(STUDENT) || userStatus.getType().equals(EXTERNAL)) userSalary = 0;
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
        maternityLeaveHoursPerDay = Math.min(fullAvailability, maternityLeaveHoursPerDay + workByDay.stream().filter(w -> w.getTaskuuid().equals(MATERNITY)).mapToDouble(WorkFull::getWorkduration).sum());

        double registeredBillableHours = workByDay.stream().filter(w -> w.getRate() > 0 && w.getWorkas() == null).mapToDouble(WorkFull::getWorkduration).sum();
        double helpedColleagueBillableHours = workByDay.stream().filter(w -> w.getRate() > 0 && w.getWorkas() != null).mapToDouble(WorkFull::getWorkduration).sum();
        double registeredAmount = workByDay.stream().filter(w -> w.getRate() > 0).mapToDouble(workFull -> workFull.getWorkduration() * workFull.getRate()).sum();

        double unavailableHours = (DateUtils.isFriday(testDay))?Math.min(2.0,fullAvailability):0.0;

        return new EmployeeDataPerDay(userStatus.getCompany(), testDay, user, fullAvailability, unavailableHours, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, registeredBillableHours, helpedColleagueBillableHours, registeredAmount, 0.0, userStatus.getType(), userStatus.getStatus(), userSalary, userStatus.isTwBonusEligible());
    }

    @Inject
    EventBus eventBus;

    /**
     * This method is called every night at 1:00
     */
    //@Transactional
    @Scheduled(every = "30s")
    //@Scheduled(cron = "0 0 1 * * ?")
    public void recalculateAvailability() {
        System.out.println("AvailabilityCalculatingExecutor.recalculateAvailability");
        employedUsers = userService.listAll(false);
        LocalDate companyStartDate = DateUtils.getCompanyStartDate();
        LocalDate testDate = companyStartDate;
        do {
            if(EmployeeDataPerDay.find("year = ?1 and month = ?2", testDate.getYear(), testDate.getMonthValue()).count()==0) {
                System.out.println("foundMissingMonth = " + testDate);
                eventBus.publish(YEAR_CHANGE_EVENT, new DateRangeMap(testDate, testDate.plusMonths(1)));
                return;
            }
            testDate = testDate.plusMonths(1);
        } while (testDate.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(3)));

        System.out.println("Finding last update");
        EmployeeDataPerDay.find("lastUpdate < ?1", LocalDateTime.now().minusDays(1)).firstResultOptional().ifPresent(employeeDataPerDay -> {
            System.out.println("found employeeDataPerDay = " + employeeDataPerDay);
            LocalDate lastUpdate = ((EmployeeDataPerDay) employeeDataPerDay).getDocumentDate();
            eventBus.publish(YEAR_CHANGE_EVENT, new DateRangeMap(lastUpdate, lastUpdate.plusMonths(1)));
        });
        System.out.println("Done");
        /*
        int monthCount = 0;
        do {
            LocalDate testDate = companyStartDate.plusMonths(monthCount);
            eventBus.publish(YEAR_CHANGE_EVENT, new DateRangeMap(testDate, testDate.plusMonths(1)));
            monthCount++;
        }  while (companyStartDate.plusMonths(monthCount).isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(3)));

         */
    }

    @Transactional
    @Scheduled(cron = "0 0 0 * * ?")
    public void recalculateElegibility() {
        employedUsers = userService.listAll(false); //findEmployedUsersByDate(testDate, false, ConsultantType.CONSULTANT);
        LocalDate testFiscalYear = DateUtils.getCurrentFiscalStartDate();
        List<EmployeeBonusEligibility> employeeBonusEligibilityList = EmployeeBonusEligibility.find("year = ?1", testFiscalYear.getYear()).list();

        employedUsers.forEach(user -> {
            //boolean foundUser = false;
            if(employeeBonusEligibilityList.stream().noneMatch(e -> e.getUser().getUuid().equals(user.getUuid()))) {
                Stream<EmployeeDataPerMonth> stream = EmployeeDataPerMonth.stream("((year = ?1 AND month >= ?2) OR (year = ?3 AND month <= ?4)) and useruuid like ?5", testFiscalYear.getYear(), testFiscalYear.getMonthValue(), testFiscalYear.plusYears(1).getYear(), testFiscalYear.plusYears(1).getMonthValue(), user.getUuid());
                double sum = stream.mapToDouble(e -> e.getAvgSalary().doubleValue()).sum();
                if(sum >0) {
                    EmployeeBonusEligibility.persist(new EmployeeBonusEligibility(user, testFiscalYear.getYear(), true, false, false, false, false, false, false, false, false, false, false, false, false));
                }
            }
        });
    }
}
