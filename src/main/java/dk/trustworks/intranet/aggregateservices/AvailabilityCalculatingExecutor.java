package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.model.EmployeeDataPerDay;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.AllArgsConstructor;
import lombok.Data;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.*;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;

@ApplicationScoped
public class AvailabilityCalculatingExecutor {

    public static final String YEAR_CHANGE_EVENT = "availability-year-calculate-consumer";
    public static final String USER_CHANGE_EVENT = "availability-user-calculate-consumer";
    public static final String USER_DAY_CHANGE_EVENT = "availability-user-day-calculate-consumer";

    private List<User> employedUsers = new ArrayList<>();

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    @Transactional
    @ConsumeEvent(value = YEAR_CHANGE_EVENT, blocking = true)
    public void process(DateRangeMap dateRangeMap) throws Exception {
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();

        System.out.println("--- Calculate Availability BEGIN "+DateUtils.stringIt(startDate)+" ---");
        //LocalDate endDate = year.plusYears(1);
        int day = 0;

        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);
        EmployeeDataPerDay.delete("month >= ?1 and month < ?2", startDate, endDate);

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
            EmployeeDataPerDay.persist(list);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("--- Calculate Availability DONE "+DateUtils.stringIt(startDate)+" ---");
    }

    @ConsumeEvent(value = USER_CHANGE_EVENT, blocking = true)
    public void createAvailabilityDocumentByUser(String useruuid) {
        System.out.println("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUser");
        System.out.println("useruuid = " + useruuid);

        User user = userService.findById(useruuid, false);
        LocalDate year = DateUtils.getCompanyStartDate();
        LocalDate endDate = year.plusYears(1);
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
            e.printStackTrace();
        }
    }

    @ConsumeEvent(value = USER_DAY_CHANGE_EVENT, blocking = true)
    public void createAvailabilityDocumentByUserAndDate(UserDateMap params) {
        System.out.println("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUserAndDate");
        System.out.println("params = " + params.useruuid);
        System.out.println("params = " + params.date);
        String useruuid = params.getUseruuid();
        LocalDate testDay = params.getDate();
        User user = userService.findById(useruuid, false);
        List<WorkFull> workList = workService.findByPeriodAndUserUUID(testDay, testDay.plusDays(1), user.getUuid());

        QuarkusTransaction.begin();
        EmployeeDataPerDay.delete("user = ?1 and month = ?2", user, testDay);
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
        Salary userSalary = userService.getUserSalary(user, testDay);

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

        if (DateUtils.isFriday(testDay)) fullAvailability = Math.max(0, fullAvailability - 2);
        return new EmployeeDataPerDay(testDay, user, fullAvailability, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, registeredBillableHours, helpedColleagueBillableHours, registeredAmount, 0.0, userStatus.getType(), userStatus.getStatus(), userSalary.getSalary());
    }

    @Inject
    EventBus bus;

    /**
     * This method is called every night at 1:00
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void recalculateAvailability() {
        employedUsers = userService.listAll(false); //findEmployedUsersByDate(testDate, false, ConsultantType.CONSULTANT);
        LocalDate companyStartDate = DateUtils.getCompanyStartDate();
        int monthCount = 0;
        do {
            LocalDate testDate = companyStartDate.plusMonths(monthCount);
            bus.publish(YEAR_CHANGE_EVENT, new DateRangeMap(testDate, testDate.plusMonths(1)));
            monthCount++;
        }  while (companyStartDate.plusMonths(monthCount).isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(3)));
        /*
        for (int i = DateUtils.getCompanyStartDate().getYear(); i < DateUtils.getCurrentFiscalStartDate().plusYears(3).getYear(); i++) {
            bus.publish(YEAR_CHANGE_EVENT, LocalDate.of(i-1, 7, 1));
        }
         */
    }

    @Data
    @AllArgsConstructor
    public static class UserDateMap {
        private String useruuid;
        private LocalDate date;
    }

    @Data
    @AllArgsConstructor
    public static class DateRangeMap {
        private LocalDate fromDate;
        private LocalDate endDate;
    }
}
