package dk.trustworks.intranet.dao.workservice.services;


import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static dk.trustworks.intranet.userservice.model.enums.StatusType.MATERNITY_LEAVE;
import static dk.trustworks.intranet.utils.DateUtils.*;


@JBossLog
@ApplicationScoped
public class WorkService {

    public static final String VACATION = "f585f46f-19c1-4a3a-9ebd-1a4f21007282";
    public static final String SICKNESS = "02bf71c5-f588-46cf-9695-5864020eb1c4";
    public static final String WORK_HOURS = "a7314f77-5e03-4f56-8b1c-0562e601f22f";
    private static final String HOURLY_TASK_UUID = "a7314f77-5e03-4f56-8b1c-0562e601f22f";

    @Inject
    EntityManager em;
    @Inject
    ProjectService projectService;

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
        return WorkFull.find("registered >= ?1 AND registered < ?2", fromDate, toDate).list();
    }

    public WorkFull findByRegisteredAndUseruuidAndTaskuuid(LocalDate registered, String useruuid, String taskuuid) {
        return WorkFull.find("registered = ?1 AND useruuid = ?2 AND taskuuid = ?3", registered, useruuid, taskuuid).firstResult();
    }

    public List<WorkFull> findByPeriodAndUserUUID(LocalDate fromdate, LocalDate todate, String useruuid) {
        if(todate.getDayOfMonth()>1) {
            log.error("Beware of month issues!!: "+todate);
        }
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND useruuid = ?3", fromdate, todate, useruuid).list();
    }

    public List<DateValueDTO> findWorkHoursByUserAndPeriod(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "select " +
                "    MAKEDATE(YEAR(wf.registered), 1) + INTERVAL (MONTH(wf.registered) - 1) MONTH AS date, " +
                "    SUM(wf.workduration) as value " +
                "from " +
                "    work_full wf " +
                "where " +
                "    useruuid = '"+useruuid+"' and " +
                "    wf.workduration > 0 and " +
                "    wf.rate > 0 " +
                "    and wf.registered >= '"+DateUtils.stringIt(fromdate)+"' and wf.registered < '"+DateUtils.stringIt(todate)+"' " +
                "group by " +
                "    YEAR(wf.registered), MONTH(wf.registered); ";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).toList();
    }

    public DateValueDTO findWorkHoursByUserAndDay(String useruuid, LocalDate day) {
        final String sql =
                "SELECT COALESCE(SUM(wf.workduration), 0) AS value " +
                        "FROM work_full wf " +
                        "WHERE wf.useruuid = :useruuid " +
                        "  AND wf.workduration > 0 " +
                        "  AND wf.rate > 0 " +
                        "  AND wf.registered = :day";

        var q = em.createNativeQuery(sql);
        q.setParameter("useruuid", useruuid);
        q.setParameter("day", java.sql.Date.valueOf(day));

        Number value = (Number) q.getSingleResult();
        return new DateValueDTO(day, value != null ? value.doubleValue() : 0d);
    }

    public List<DateValueDTO> findWorkRevenueByUserAndPeriod(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "select " +
                "    MAKEDATE(YEAR(wf.registered), 1) + INTERVAL (MONTH(wf.registered) - 1) MONTH AS date, " +
                "    SUM(wf.workduration * wf.rate) as value " +
                "from " +
                "    work_full wf " +
                "where " +
                "    useruuid = '"+useruuid+"' and " +
                "    wf.workduration > 0 and " +
                "    wf.rate > 0 " +
                "    and wf.registered >= '"+DateUtils.stringIt(fromdate)+"' and wf.registered < '"+DateUtils.stringIt(todate)+"' " +
                "group by " +
                "    YEAR(wf.registered), MONTH(wf.registered); ";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).toList();
    }

    public DateValueDTO findWorkRevenueByUserAndDay(String useruuid, LocalDate day) {
        final String sql =
                "SELECT COALESCE(SUM(wf.workduration * wf.rate), 0) AS value " +
                        "FROM work_full wf " +
                        "WHERE wf.useruuid = :useruuid " +
                        "  AND wf.workduration > 0 " +
                        "  AND wf.rate > 0 " +
                        "  AND wf.registered = :day";

        var q = em.createNativeQuery(sql);
        q.setParameter("useruuid", useruuid);
        q.setParameter("day", java.sql.Date.valueOf(day));

        Object raw = q.getSingleResult();
        java.math.BigDecimal revenue = (raw == null)
                ? java.math.BigDecimal.ZERO
                : (raw instanceof java.math.BigDecimal)
                ? (java.math.BigDecimal) raw
                : new java.math.BigDecimal(raw.toString());

        return new DateValueDTO(day, revenue.doubleValue());
    }

    public List<WorkFull> findByPeriodAndProject(String fromdate, String todate, String projectuuid) {
        if(dateIt(todate).getDayOfMonth()>1) log.error("Beware of month issues!!: "+todate);
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND projectuuid LIKE ?3", dateIt(fromdate), dateIt(todate), projectuuid).list();
    }

    public List<WorkFull> findByTasks(List<String> taskuuids) {
        return WorkFull.find("taskuuid IN (?1)", taskuuids).list();
    }

    public List<WorkFull> findByTask(String taskuuid) {
        return WorkFull.find("taskuuid LIKE ?1", taskuuid).list();
    }

    public List<Work> findByUserAndUnpaidAndTaskuuid(String useruuid, String taskuuid) {
        return Work.find("useruuid = ?1 AND taskuuid = ?2 AND paidOut is null", useruuid, taskuuid).list();
    }

    public List<Work> findByUserAndPaidOutMonthAndTaskuuid(String useruuid, String taskuuid, LocalDate month) {
        return Work.find("useruuid = ?1 AND taskuuid = ?2 AND " +
                        "(YEAR(paidOut) = YEAR(?3) AND MONTH(paidOut) = MONTH(?3))",
                useruuid, taskuuid, month).list();
    }

    public List<Work> findByUserAndUnpaidAndMonthAndTaskuuid(String useruuid, String taskuuid, LocalDate month) {
        return Work.find("useruuid = ?1 AND taskuuid = ?2 AND " +
                "(paidOut is null OR YEAR(paidOut) = YEAR(?3) AND MONTH(paidOut) = MONTH(?3))",
                useruuid, taskuuid, month).list();
    }

    public List<Work> findByUseruuidAndTaskuuidAndPeriod(String useruuid, String taskuuid, LocalDate fromdate, LocalDate todate) {
        return WorkFull.find("useruuid LIKE ?1 AND taskuuid LIKE ?2 AND registered >= ?3 AND registered < ?4", useruuid, taskuuid, fromdate, todate).list();
    }

    public List<WorkFull> findByContract(String contractuuid) {
        return WorkFull.find("contractuuid LIKE ?1", contractuuid).list();
    }

    public List<WorkFull> findWorkFullByUserAndTasks(String useruuid, String taskuuids) {
        return WorkFull.find("useruuid LIKE ?1 AND taskuuid IN (?2)", useruuid, taskuuids).list();
    }

    public List<Work> findWorkByUserAndTasks(String useruuid, String taskuuids) {
        return Work.find("useruuid LIKE ?1 AND taskuuid IN (?2)", useruuid, taskuuids).list();
    }

    public List<WorkFull> findVacationByUser(String useruuid) {
        return findWorkFullByUserAndTasks(useruuid, VACATION);
    }

    public List<Work> findWorkVacationByUser(String useruuid) {
        return findWorkByUserAndTasks(useruuid, VACATION);
    }

    public double calculateVacationByUserInMonth(String useruuid, LocalDate fromDate, LocalDate toDate) {
        return findByPeriodAndUserAndTasks(fromDate, toDate, useruuid, VACATION).stream().mapToDouble(WorkFull::getWorkduration).sum();
    }

    public Map<String, Map<String, Double>> findVacationSumByMonth() {
        return findByUserAndTasksSumByMonth(VACATION);
    }

    public List<WorkFull> findSicknessByUser(String useruuid) {
        return findWorkFullByUserAndTasks(useruuid, SICKNESS);
    }

    public Map<String, Map<String, Double>> findSicknessSumByMonth() {
        return findByUserAndTasksSumByMonth(SICKNESS);
    }

    public List<WorkFull> findMaternityLeaveByUser(String useruuid) {
        return findWorkFullByUserAndTasks(useruuid, MATERNITY_LEAVE.getTaskuuid());
    }

    public Map<String, Map<String, Double>> findMaternityLeaveSumByMonth() {
        return findByUserAndTasksSumByMonth(MATERNITY_LEAVE.getTaskuuid());
    }

    /*
     * returns Map of users with
     * map of dates and hours
     */
    public Map<String, Map<String, Double>> findByUserAndTasksSumByMonth(@QueryParam("taskuuids") String taskuuids) {
        log.info("WorkResource.findByUserAndTasksSumByMonth");
        log.info("taskuuids = " + taskuuids);
        List<WorkFull> workList = WorkFull.find("taskuuid IN (?1)", taskuuids).list();
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

    public List<WorkFull> findByPeriodAndUserAndTasks(LocalDate fromdate, LocalDate todate, String useruuid, String taskuuids) {
        if(todate.getDayOfMonth()>1) log.error("Beware of month issues!!: "+todate);
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND useruuid LIKE ?3 AND taskuuid IN (?4)", fromdate, todate, useruuid, taskuuids).list();
    }

    public List<WorkFull> findByContractAndUserByPeriod(String contractuuid, String useruuid, LocalDate fromDate, LocalDate toDate) {
        return WorkFull.find("contractuuid = ?1 AND useruuid = ?2 AND registered >= ?3 AND registered < ?4", contractuuid, useruuid, fromDate, toDate).list();
    }

    public List<WorkFull> findByContractAndUser(String contractuuid, String useruuid) {
        return WorkFull.find("contractuuid = ?1 AND useruuid = ?2", contractuuid, useruuid).list();
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



    @Transactional
    @CacheInvalidateAll(cacheName = "work-cache")
    @CacheInvalidateAll(cacheName = "employee-availability")
    public void persistOrUpdate(Work work) {
        List<Work> workList = Work.find("registered = ?1 AND useruuid LIKE ?2 AND taskuuid LIKE ?3", work.getRegistered(), work.getUseruuid(), work.getTaskuuid()).list();
        if(!workList.isEmpty()) {
            if(workList.stream().findFirst().get().isPaidOut()) return;
            work.setUuid(workList.stream().findFirst().get().getUuid());
            Work.update("workduration = ?1, comments = ?2, paidOut = ?3 WHERE registered = ?4 AND useruuid LIKE ?5 AND taskuuid LIKE ?6", work.getWorkduration(), work.getComments(), work.getPaidOut(), work.getRegistered(), work.getUseruuid(), work.getTaskuuid());
            log.info("Updating work via save: "+work);
        } else {
            work.setUuid(UUID.randomUUID().toString());
            Work.persist(work);
            log.info("Saving work: "+work);
        }
    }

    public List<WorkFull> findBillableWorkByUser(String useruuid) {
        return WorkFull.find("useruuid like ?1", useruuid).list();
    }

    public double sumBillableByUserAndTasks(String useruuid, LocalDate localDate) {
        return WorkFull.<WorkFull>find("useruuid like ?1 AND rate > 0 AND registered >= ?2 AND registered < ?3", useruuid, localDate, localDate.plusMonths(1)).stream().mapToDouble(WorkFull::getWorkduration).sum();
    }

    @Transactional
    public void setPaidAndUpdate(Work work) {
        work.setPaidOut(LocalDateTime.now());
        Work.update("paidOut = ?1 WHERE uuid like ?2 ", work.getPaidOut(), work.getUuid());
    }

    @Transactional
    public void clearPaidAndUpdate(Work work) {
        work.setPaidOut(null);
        Work.update("paidOut = ?1 WHERE uuid like ?2 ", work.getPaidOut(), work.getUuid());
    }

    @Transactional
    public void registerAsPaidout(String contractuuid, String projectuuid, int month, int year) {
        LocalDateTime now = LocalDateTime.now();
        WorkFull.<WorkFull>find("contractuuid = ?1 AND projectuuid = ?2 AND MONTH(registered) = ?3 AND YEAR(registered) = ?4", contractuuid, projectuuid, month, year).stream().forEach(work -> {
            Work.update("paidOut = ?1 WHERE uuid = ?2 ", now, work.getUuid());
        });
    }

    /**
     * Returns a paginated list of hourly work items for the given user,
     * filtering so that only items with paidOut null or within the last 6 months are returned.
     */
    public List<Work> findHourlyWorkPaged(String useruuid, int page, int size) {
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
        return Work.find(
                        "useruuid = ?1 and taskuuid = ?2 and workduration > 0 and (paidOut is null or paidOut >= ?3) order by paidOut desc",
                        useruuid, HOURLY_TASK_UUID, sixMonthsAgo)
                .page(Page.of(page, size))
                .list();
    }

    /**
     * Returns the total count of hourly work items for the given user,
     * filtering so that only items with paidOut null or within the last 6 months are counted.
     */
    public long countHourlyWork(String useruuid) {
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
        return Work.count(
                "useruuid = ?1 and taskuuid = ?2 and workduration > 0 and (paidOut is null or paidOut >= ?3)",
                useruuid, HOURLY_TASK_UUID, sixMonthsAgo);
    }

    /**
     * Returns all hourly work items for the given user,
     * filtering so that only items with paidOut null or within the last 6 months are returned.
     */
    public List<Work> findHourlyWork(String useruuid) {
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
        return Work.find(
                        "useruuid = ?1 and taskuuid = ?2 and workduration > 0 and (paidOut is null or paidOut >= ?3) order by paidOut desc",
                        useruuid, HOURLY_TASK_UUID, sixMonthsAgo)
                .list();
    }
}
