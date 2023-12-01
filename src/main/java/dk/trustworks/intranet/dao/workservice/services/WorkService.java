package dk.trustworks.intranet.dao.workservice.services;


import dk.trustworks.intranet.aggregates.sender.SystemEventSender;
import dk.trustworks.intranet.aggregates.work.events.UpdateWorkEvent;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.messaging.dto.UserDateMap;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.quarkus.panache.common.Page;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.GET;
import javax.ws.rs.QueryParam;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static dk.trustworks.intranet.utils.DateUtils.*;


@JBossLog
@ApplicationScoped
public class WorkService {

    public static final String VACATION = "f585f46f-19c1-4a3a-9ebd-1a4f21007282";
    public static final String SICKNESS = "02bf71c5-f588-46cf-9695-5864020eb1c4";
    public static final String MATERNITY = "da2f89fc-9aef-4029-8ac2-7486be60e9b9";

    @GET
    public List<WorkFull> listAll(int page) {
        return WorkFull.findAll().page(Page.of(page, 1000)).list();
    }

    /**
     * Including fromDate. Excluding toDate.
     * @param fromDate Including
     * @param toDate Excluding
     * @return List of WorkFull
     */
    public List<WorkFull> findByPeriod(LocalDate fromDate, LocalDate toDate) {
        if(toDate.getDayOfMonth()>1) {
            log.error("Beware of month issues!!: "+toDate);
        }
        return WorkFull.find("registered >= ?1 AND registered < ?2", fromDate, toDate).list();
    }

    public WorkFull findByRegisteredAndUseruuidAndTaskuuid(LocalDate registered, String useruuid, String taskuuid) {
        return WorkFull.find("registered = ?1 AND useruuid = ?2 AND taskuuid = ?3", registered, useruuid, taskuuid).firstResult();
    }

    public List<WorkFull> findByPeriodAndUserUUID(LocalDate fromdate, LocalDate todate, String useruuid) {
        if(todate.getDayOfMonth()>1) {
            log.error("Beware of month issues!!: "+todate);
        }
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND useruuid LIKE ?3", fromdate, todate, useruuid).list();
    }

    public List<WorkFull> findByPeriodAndProject(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate, @QueryParam("projectuuid") String projectuuid) {
        if(dateIt(todate).getDayOfMonth()>1) log.error("Beware of month issues!!: "+todate);
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND projectuuid LIKE ?3", dateIt(fromdate), dateIt(todate), projectuuid).list();
    }

    public List<WorkFull> findByTasks(@QueryParam("taskuuids") List<String> taskuuids) {
        return WorkFull.find("taskuuid IN (?1)", taskuuids).list();
    }

    public List<WorkFull> findByTask(@QueryParam("taskuuid") String taskuuid) {
        return WorkFull.find("taskuuid LIKE ?1", taskuuid).list();
    }

    public List<WorkFull> findByContract(@QueryParam("contractuuid") String contractuuid) {
        return WorkFull.find("contractuuid LIKE ?1 and rate > 0", contractuuid).list();
    }

    @CacheResult(cacheName = "work-cache")
    public List<WorkFull> findByUserAndTasks(String useruuid, String taskuuids) {
        return WorkFull.find("useruuid LIKE ?1 AND taskuuid IN (?2)", useruuid, taskuuids).list();
    }

    public List<WorkFull> findVacationByUser(String useruuid) {
        return findByUserAndTasks(useruuid, VACATION);
    }

    public Map<String, Map<String, Double>> findVacationSumByMonth() {
        return findByUserAndTasksSumByMonth(VACATION);
    }

    public List<WorkFull> findSicknessByUser(String useruuid) {
        return findByUserAndTasks(useruuid, SICKNESS);
    }

    public Map<String, Map<String, Double>> findSicknessSumByMonth() {
        return findByUserAndTasksSumByMonth(SICKNESS);
    }

    public List<WorkFull> findMaternityLeaveByUser(String useruuid) {
        return findByUserAndTasks(useruuid, MATERNITY);
    }

    public Map<String, Map<String, Double>> findMaternityLeaveSumByMonth() {
        return findByUserAndTasksSumByMonth(MATERNITY);
    }

    @CacheResult(cacheName = "work-cache")
    public int getWorkDaysInMonth(String userUUID, LocalDate month) {
        int weekDays = DateUtils.getWeekdaysInPeriod(getFirstDayOfMonth(month), getFirstDayOfMonth(month).plusMonths(1));
        List<WorkFull> workList = findByPeriodAndUserAndTasks(stringIt(getFirstDayOfMonth(month.getYear(), month.getMonthValue())), getLastDayOfMonth(month.getYear(), month.getMonthValue()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), userUUID,"02bf71c5-f588-46cf-9695-5864020eb1c4, f585f46f-19c1-4a3a-9ebd-1a4f21007282");
        double vacationAndSickdays = workList.stream().mapToDouble(WorkFull::getWorkduration).sum() / 7.4;
        weekDays -= vacationAndSickdays;
        return weekDays;
    }

    /*
     * returns Map of users with
     * map of dates and hours
     */
    public Map<String, Map<String, Double>> findByUserAndTasksSumByMonth(@QueryParam("taskuuids") String taskuuids) {
        log.info("WorkResource.findByUserAndTasksSumByMonth");
        log.info("taskuuids = " + taskuuids);
        List<WorkFull> workList = WorkFull.find("taskuuid IN (?1)", taskuuids).list();
        log.info("workList.size() = " + workList.size());
        Map<String, Map<String, Double>> userMap = new HashMap<>();
        for (WorkFull work : workList) {
            userMap.putIfAbsent(work.getUseruuid(), new HashMap<>());
            Map<String, Double> dateHoursMap = userMap.get(work.getUseruuid());

            dateHoursMap.putIfAbsent(stringIt(work.getRegistered().withDayOfMonth(1)), 0.0);
            double hours = dateHoursMap.get(stringIt(work.getRegistered().withDayOfMonth(1)));

            hours += (work.getRegistered().getDayOfWeek().getValue() == DayOfWeek.FRIDAY.getValue() && work.getWorkduration()>5.4)?5.4:work.getWorkduration();

            dateHoursMap.put(stringIt(work.getRegistered().withDayOfMonth(1)), hours);
        }
        return userMap;
    }

    public double countByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        try (Stream<WorkFull> workStream = WorkFull.stream("useruuid LIKE ?1 AND taskuuid IN (?2)", useruuid, taskuuids)) {
            return workStream.mapToDouble(WorkFull::getWorkduration).sum();
        }
    }

    public List<WorkFull> findByPeriodAndUserAndTasks(String fromdate, String todate, String useruuid, String taskuuids) {
        if(dateIt(todate).getDayOfMonth()>1) log.error("Beware of month issues!!: "+todate);
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND useruuid LIKE ?3 AND taskuuid IN (?4)", dateIt(fromdate), dateIt(todate), useruuid, taskuuids).list();
    }


    public List<WorkFull> findByYearAndMonth(LocalDate month) {
        return findByPeriod(getFirstDayOfMonth(month), getFirstDayOfMonth(month).plusMonths(1));
    }

    //@Cacheable(value = "work")
    public List<WorkFull> findByYearAndMonthAndProject(int year, int month, String projectuuid) {
        return findByPeriodAndProject(getFirstDayOfMonth(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), getFirstDayOfMonth(year, month).plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), projectuuid);
    }

    //@Cacheable("work")
    public Double findAmountUsedByContract(String contractuuid) {
        return findByContract(contractuuid).stream().mapToDouble(value -> value.getRate()*value.getWorkduration()).sum();
    }


    public Double findHoursRegisteredOnContractByPeriod(String contractuuid, String useruuid, LocalDate fromdate, LocalDate todate) {
        List<WorkFull> workFullList = WorkFull.find("contractuuid like ?1 and useruuid like ? and registered >= ?3 and registered < ?4", contractuuid, useruuid, fromdate, todate).list();
        return workFullList.stream().mapToDouble(WorkFull::getWorkduration).sum();

    }

    @Inject
    SystemEventSender sender;

    @Transactional
    @CacheInvalidateAll(cacheName = "work-cache")
    public void saveWork(Work work) {
        List<Work> workList = Work.find("registered = ?1 AND useruuid LIKE ?2 AND taskuuid LIKE ?3", work.getRegistered(), work.getUseruuid(), work.getTaskuuid()).list();
        if(workList.size()>0) {
            work.setUuid(workList.stream().findFirst().get().getUuid());
            Work.update("workduration = ?1 WHERE registered = ?2 AND useruuid LIKE ?3 AND taskuuid LIKE ?4", work.getWorkduration(), work.getRegistered(), work.getUseruuid(), work.getTaskuuid());
            log.info("Updating work via save: "+work);
        } else {
            work.setUuid(UUID.randomUUID().toString());
            Work.persist(work);
            log.info("Saving work: "+work);
        }
        sender.handleEvent(new UpdateWorkEvent(new UserDateMap(work.getUseruuid(), work.getRegistered())));
        if(work.getWorkas()!=null && !work.getWorkas().isEmpty())
            sender.handleEvent(new UpdateWorkEvent(new UserDateMap(work.getWorkas(), work.getRegistered())));
    }

    public List<WorkFull> findBillableWorkByUser(String useruuid) {
        return WorkFull.find("useruuid like ?1", useruuid).list();
    }
}
