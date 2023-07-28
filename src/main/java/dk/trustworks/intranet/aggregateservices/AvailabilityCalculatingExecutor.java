package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.AvailabilityPerDayDocument;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.eventbus.EventBus;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.*;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;

@ApplicationScoped
public class AvailabilityCalculatingExecutor {

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    //@PersistenceContext
    //EntityManager em;
/*
    //@Transactional
    @Scheduled(every = "20m")
    public void calcAvailability() {
        LocalDate startDate = LocalDate.of(2014,3,1);
        LocalDate endDate = DateUtils.getCurrentFiscalStartDate().plusYears(2);
        int day = 0;

        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);
        System.out.println("--- Calculate Availability BEGIN ---");
        do {
            LocalDate testDate = startDate.plusDays(day);
            List<User> employedUsers = userService.findEmployedUsersByDate(testDate, false, ConsultantType.CONSULTANT);

            ArrayList<AvailabilityPerDayDocument> list = new ArrayList<>();
            QuarkusTransaction.begin();
            for (User user : employedUsers) {
                if(!user.getUuid().equals("7948c5e8-162c-4053-b905-0f59a21d7746")) continue;
                UserStatus userStatus = userService.getUserStatus(user, testDate);
                double fullAvailability = userStatus.getAllocation() / 5.0; // 7.4
                if(!DateUtils.isWorkday(testDate)) fullAvailability = 0.0;
                if(DateUtils.isFriday(testDate)) fullAvailability -= 2;

                List<WorkFull> workByDay = workList.stream().filter(w -> w.getRegistered().isEqual(testDate)).toList();

                double nonPaydLeaveHoursPerday = userStatus.getStatus().equals(NON_PAY_LEAVE)?fullAvailability:0.0;
                double paidLeaveHoursPerDay = userStatus.getStatus().equals(PAID_LEAVE)?fullAvailability:0.0;
                double maternityLeaveHoursPerDay = userStatus.getStatus().equals(MATERNITY_LEAVE)?fullAvailability:0.0;

                double vacationHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(VACATION)).mapToDouble(w -> w.getWorkduration()).sum());
                double sicknessHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(SICKNESS)).mapToDouble(w -> w.getWorkduration()).sum());
                maternityLeaveHoursPerDay = Math.min(fullAvailability, maternityLeaveHoursPerDay + workByDay.stream().filter(w -> w.getTaskuuid().equals(MATERNITY)).mapToDouble(w -> w.getWorkduration()).sum());

                list.add(new AvailabilityPerDayDocument(testDate, user, fullAvailability, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, userStatus.getType(), userStatus.getStatus()));
            }
            AvailabilityPerDayDocument.persist(list);
            //AvailabilityPerDayDocument.getEntityManager().flush();
            //AvailabilityPerDayDocument.getEntityManager().clear();
            //em.flush();
            //em.clear();
            QuarkusTransaction.commit();
            System.out.println("day = " + DateUtils.stringIt(startDate.plusDays(day)));
            day++;
        } while (startDate.plusDays(day).isBefore(endDate));
        em.flush();
        System.out.println("--- Calculate Availability DONE ---");
    }

 */

    @ConsumeEvent(value = "availability-calculate-consumer", blocking = true)
    public void process(LocalDate year) throws Exception {
        LocalDate startDate = year;
        LocalDate endDate = year.plusYears(1);
        int day = 0;

        List<WorkFull> workList = workService.findByPeriod(startDate, endDate);
        System.out.println("--- Calculate Availability BEGIN "+DateUtils.stringIt(startDate)+" ---");
        do {
            LocalDate testDate = startDate.plusDays(day);
            List<User> employedUsers = userService.findEmployedUsersByDate(testDate, false, ConsultantType.CONSULTANT);

            ArrayList<AvailabilityPerDayDocument> list = new ArrayList<>();
            QuarkusTransaction.begin();
            for (User user : employedUsers) {
                if(!user.getUuid().equals("7948c5e8-162c-4053-b905-0f59a21d7746")) continue;
                UserStatus userStatus = userService.getUserStatus(user, testDate);
                double fullAvailability = userStatus.getAllocation() / 5.0; // 7.4
                if(!DateUtils.isWorkday(testDate)) fullAvailability = 0.0;
                if(DateUtils.isFriday(testDate)) fullAvailability -= 2;

                List<WorkFull> workByDay = workList.stream().filter(w -> w.getRegistered().isEqual(testDate)).toList();

                double nonPaydLeaveHoursPerday = userStatus.getStatus().equals(NON_PAY_LEAVE)?fullAvailability:0.0;
                double paidLeaveHoursPerDay = userStatus.getStatus().equals(PAID_LEAVE)?fullAvailability:0.0;
                double maternityLeaveHoursPerDay = userStatus.getStatus().equals(MATERNITY_LEAVE)?fullAvailability:0.0;

                double vacationHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(VACATION)).mapToDouble(w -> w.getWorkduration()).sum());
                double sicknessHoursPerDay = Math.min(fullAvailability, workByDay.stream().filter(w -> w.getTaskuuid().equals(SICKNESS)).mapToDouble(w -> w.getWorkduration()).sum());
                maternityLeaveHoursPerDay = Math.min(fullAvailability, maternityLeaveHoursPerDay + workByDay.stream().filter(w -> w.getTaskuuid().equals(MATERNITY)).mapToDouble(w -> w.getWorkduration()).sum());

                list.add(new AvailabilityPerDayDocument(testDate, user, fullAvailability, vacationHoursPerDay, sicknessHoursPerDay, maternityLeaveHoursPerDay, nonPaydLeaveHoursPerday, paidLeaveHoursPerDay, userStatus.getType(), userStatus.getStatus()));
            }
            AvailabilityPerDayDocument.persist(list);

            QuarkusTransaction.commit();
            System.out.println("day = " + DateUtils.stringIt(startDate.plusDays(day)));
            day++;
        } while (startDate.plusDays(day).isBefore(endDate));
        System.out.println("--- Calculate Availability DONE ---");
    }

    @Inject
    EventBus bus;

    @Scheduled(every = "20m")
    public Uni<String> greeting() {
        bus.<String>requestAndForget("greeting", name);
    }
}
