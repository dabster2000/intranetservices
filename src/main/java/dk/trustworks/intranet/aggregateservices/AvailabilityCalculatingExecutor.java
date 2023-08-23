package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregateservices.messaging.DateRangeMap;
import dk.trustworks.intranet.aggregateservices.messaging.MessageEmitter;
import dk.trustworks.intranet.aggregateservices.messaging.UserDateMap;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.EmployeeDataPerMonth;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import dk.trustworks.intranet.model.EmployeeDataPerDay;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static dk.trustworks.intranet.aggregateservices.messaging.MessageEmitter.*;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.*;
import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.EXTERNAL;
import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.STUDENT;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;

@ApplicationScoped
public class AvailabilityCalculatingExecutor {

    private List<User> employedUsers = new ArrayList<>();

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    @Transactional
    @Incoming(value = READ_YEAR_CHANGE_EVENT)
    public void process(JsonObject message) throws Exception {
        DateRangeMap dateRangeMap = message.mapTo(DateRangeMap.class);
        LocalDate startDate = dateRangeMap.getFromDate();
        LocalDate endDate = dateRangeMap.getEndDate();

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
    }

    @Transactional
    @Incoming(value = READ_USER_CHANGE_EVENT)
    public void createAvailabilityDocumentByUser(String useruuid) {
        System.out.println("AvailabilityCalculatingExecutor.createAvailabilityDocumentByUser");
        System.out.println("useruuid = " + useruuid);
        User user = userService.findById(useruuid, false);
        LocalDate year = DateUtils.getCompanyStartDate();
        LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(3);
        int day = 0;

        List<WorkFull> workList = workService.findByPeriodAndUserUUID(year, endDate, user.getUuid());

        EmployeeDataPerDay.delete("user = ?1", user);
        System.out.println("1");
        try {
            ArrayList<EmployeeDataPerDay> list = new ArrayList<>();
            do {
                LocalDate testDay = year.plusDays(day);
                EmployeeDataPerDay document = createAvailabilityDocumentByUserAndDate(user, testDay, workList);
                list.add(document);
                day++;
            } while (year.plusDays(day).isBefore(endDate));
            EmployeeDataPerDay.persist(list);
            System.out.println("2");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Transactional
    @Incoming(value = READ_USER_DAY_CHANGE_EVENT)
    public void createAvailabilityDocumentByUserAndDate(JsonObject message) {
        UserDateMap userDateMap = message.mapTo(UserDateMap.class);
        String useruuid = userDateMap.getUseruuid();
        LocalDate testDay = userDateMap.getDate();
        User user = userService.findById(useruuid, false);
        List<WorkFull> workList = workService.findByPeriodAndUserUUID(testDay, testDay.plusDays(1), user.getUuid());

        EmployeeDataPerDay.delete("user = ?1 and month = ?2", user, testDay);

        try {
            EmployeeDataPerDay document = createAvailabilityDocumentByUserAndDate(userService.findById(user.getUuid(), false), testDay, workList);
            EmployeeDataPerDay.persist(document);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private EmployeeDataPerDay createAvailabilityDocumentByUserAndDate(User user, LocalDate testDay, List<WorkFull> workList) {
        UserStatus userStatus = userService.getUserStatus(user, testDay);
        int userSalary = userService.getUserSalary(user, testDay).getSalary();
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
        //if (DateUtils.isFriday(testDay)) fullAvailability = Math.max(0, fullAvailability - 2);

        return new EmployeeDataPerDay(testDay, user, fullAvailability, unavailableHours, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, registeredBillableHours, helpedColleagueBillableHours, registeredAmount, 0.0, userStatus.getType(), userStatus.getStatus(), userSalary, userStatus.isTwBonusEligible());
    }

    @Inject
    MessageEmitter messageEmitter;

    /**
     * This method is called every night at 1:00
     */
    @Transactional
    //@Scheduled(every = "24h")
    @Scheduled(cron = "0 0 1 * * ?")
    public void recalculateAvailability() {
        LocalDate testFiscalYear = DateUtils.getCurrentFiscalStartDate().minusYears(2);

        employedUsers = userService.listAll(false); //findEmployedUsersByDate(testDate, false, ConsultantType.CONSULTANT);
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

        LocalDate companyStartDate = DateUtils.getCompanyStartDate();
        int monthCount = 0;
        do {
            LocalDate testDate = companyStartDate.plusMonths(monthCount);
            messageEmitter.sendYearChange(new DateRangeMap(testDate, testDate.plusMonths(1)));
            monthCount++;
        }  while (companyStartDate.plusMonths(monthCount).isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(3)));
    }


}
