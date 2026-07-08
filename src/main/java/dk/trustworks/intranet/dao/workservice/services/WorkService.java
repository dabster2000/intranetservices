package dk.trustworks.intranet.dao.workservice.services;


import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.work.ConsultantWorkRevenue;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.hibernate.exception.ConstraintViolationException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /**
     * Self-proxy. Lets {@link #persistOrUpdate(Work)} re-enter the transactional unit of work
     * {@link #persistOrUpdateInTx(Work)} through the CDI/interceptor stack so the retry runs in a
     * <b>fresh</b> transaction (a plain {@code this.} call would bypass {@code @Transactional} via
     * self-invocation and reuse the poisoned session).
     */
    @Inject
    WorkService self;

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

    /**
     * Optimized method for fetching work data by period with pagination.
     * Use this for large date ranges to avoid memory issues.
     * @param fromDate Including
     * @param toDate Excluding
     * @param page Page number (0-based)
     * @param pageSize Number of records per page
     * @return List of WorkFull
     */
    public List<WorkFull> findByPeriodPaged(LocalDate fromDate, LocalDate toDate, int page, int pageSize) {
        return WorkFull.find("registered >= ?1 AND registered < ?2", fromDate, toDate)
                .page(Page.of(page, pageSize))
                .list();
    }

    /**
     * Stream work data for memory-efficient processing of large datasets.
     * Remember to close the stream when done.
     * @param fromDate Including
     * @param toDate Excluding
     * @return Stream of WorkFull
     */
    public Stream<WorkFull> findByPeriodStream(LocalDate fromDate, LocalDate toDate) {
        return WorkFull.find("registered >= ?1 AND registered < ?2", fromDate, toDate)
                .stream();
    }

    /**
     * Lightweight query that returns only essential work data fields.
     * Uses native query to fetch only necessary columns for better performance.
     * @param fromDate Including
     * @param toDate Excluding
     * @return List of lightweight work data
     */
    public List<Map<String, Object>> findByPeriodLightweight(LocalDate fromDate, LocalDate toDate) {
        String sql = "SELECT w.uuid, w.useruuid, w.registered, w.workduration, " +
                     "w.taskuuid, w.billable, w.rate, t.projectuuid " +
                     "FROM work w " +
                     "LEFT JOIN task t ON w.taskuuid = t.uuid " +
                     "WHERE w.registered >= :fromDate AND w.registered < :toDate " +
                     "ORDER BY w.registered, w.useruuid";

        List<Object[]> results = em.createNativeQuery(sql)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(row -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("uuid", row[0]);
                    map.put("useruuid", row[1]);
                    map.put("registered", (LocalDate) row[2]);
                    map.put("workduration", row[3]);
                    map.put("taskuuid", row[4]);
                    map.put("billable", row[5]);
                    map.put("rate", row[6]);
                    map.put("projectuuid", row[7]);
                    return map;
                })
                .collect(Collectors.toList());
    }

    /**
     * Count total records for a period without fetching all data.
     * Useful for pagination metadata.
     * @param fromDate Including
     * @param toDate Excluding
     * @return Total count of work records
     */
    public long countByPeriod(LocalDate fromDate, LocalDate toDate) {
        return WorkFull.count("registered >= ?1 AND registered < ?2", fromDate, toDate);
    }

    /**
     * Batch fetch work data grouped by user for performance.
     * Optimized for yearly data processing with minimal memory footprint.
     * @param fromDate Including
     * @param toDate Excluding
     * @return Map of user UUID to their work data
     */
    public Map<String, List<WorkFull>> findByPeriodGroupedByUser(LocalDate fromDate, LocalDate toDate) {
        String sql = "SELECT useruuid FROM work " +
                     "WHERE registered >= :fromDate AND registered < :toDate " +
                     "GROUP BY useruuid";

        List<String> userIds = em.createNativeQuery(sql)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        Map<String, List<WorkFull>> result = new HashMap<>();

        // Fetch data for each user in batches to avoid memory issues
        for (String userId : userIds) {
            List<WorkFull> userWork = WorkFull.find(
                "useruuid = ?1 AND registered >= ?2 AND registered < ?3",
                userId, fromDate, toDate
            ).list();
            result.put(userId, userWork);
        }

        return result;
    }

    /**
     * Optimized method to get work summary by period.
     * Returns aggregated data without loading full entities.
     * @param fromDate Including
     * @param toDate Excluding
     * @return Aggregated work summary
     */
    public Map<String, Object> getWorkSummaryByPeriod(LocalDate fromDate, LocalDate toDate) {
        String sql = "SELECT " +
                     "COUNT(DISTINCT w.useruuid) as unique_users, " +
                     "COUNT(DISTINCT w.taskuuid) as unique_tasks, " +
                     "COUNT(DISTINCT t.projectuuid) as unique_projects, " +
                     "SUM(w.workduration) as total_hours, " +
                     "SUM(w.workduration * w.rate) as total_revenue, " +
                     "COUNT(*) as total_entries " +
                     "FROM work w " +
                     "LEFT JOIN task t ON w.taskuuid = t.uuid " +
                     "WHERE w.registered >= :fromDate AND w.registered < :toDate";

        Object[] result = (Object[]) em.createNativeQuery(sql)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getSingleResult();

        Map<String, Object> summary = new HashMap<>();
        summary.put("uniqueUsers", ((Number) result[0]).intValue());
        summary.put("uniqueTasks", ((Number) result[1]).intValue());
        summary.put("uniqueProjects", ((Number) result[2]).intValue());
        summary.put("totalHours", result[3] != null ? ((Number) result[3]).doubleValue() : 0.0);
        summary.put("totalRevenue", result[4] != null ? ((Number) result[4]).doubleValue() : 0.0);
        summary.put("totalEntries", ((Number) result[5]).longValue());
        summary.put("fromDate", fromDate);
        summary.put("toDate", toDate);

        return summary;
    }

    public WorkFull findByRegisteredAndUseruuidAndTaskuuid(LocalDate registered, String useruuid, String taskuuid) {
        return WorkFull.find("registered = ?1 AND useruuid = ?2 AND taskuuid = ?3", registered, useruuid, taskuuid).firstResult();
    }

    public List<WorkFull> findByPeriodAndUserUUID(LocalDate fromdate, LocalDate todate, String useruuid) {
        if(todate.getDayOfMonth()>1) {
            log.debug("Non-month-aligned period end (todate not the 1st): "+todate);
        }
        return WorkFull.find("registered >= ?1 AND registered < ?2 AND useruuid = ?3", fromdate, todate, useruuid).list();
    }

    @SuppressWarnings("unchecked")
    public List<DateValueDTO> findWorkHoursByUserAndPeriod(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT " +
                "    MAKEDATE(YEAR(wf.registered), 1) + INTERVAL (MONTH(wf.registered) - 1) MONTH AS date, " +
                "    SUM(wf.workduration) AS value " +
                "FROM work_full wf " +
                "WHERE wf.useruuid = :useruuid " +
                "    AND wf.workduration > 0 " +
                "    AND wf.rate > 0 " +
                "    AND wf.registered >= :fromdate " +
                "    AND wf.registered < :todate " +
                "GROUP BY YEAR(wf.registered), MONTH(wf.registered)";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("fromdate", fromdate)
                .setParameter("todate", todate)
                .getResultList()).stream()
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
        q.setParameter("day", day);

        Number value = (Number) q.getSingleResult();
        return new DateValueDTO(day, value != null ? value.doubleValue() : 0d);
    }

    @SuppressWarnings("unchecked")
    public List<DateValueDTO> findWorkRevenueByUserAndPeriod(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT " +
                "    MAKEDATE(YEAR(wf.registered), 1) + INTERVAL (MONTH(wf.registered) - 1) MONTH AS date, " +
                "    SUM(wf.workduration * wf.rate) AS value " +
                "FROM work_full wf " +
                "WHERE wf.useruuid = :useruuid " +
                "    AND wf.workduration > 0 " +
                "    AND wf.rate > 0 " +
                "    AND wf.registered >= :fromdate " +
                "    AND wf.registered < :todate " +
                "GROUP BY YEAR(wf.registered), MONTH(wf.registered)";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("fromdate", fromdate)
                .setParameter("todate", todate)
                .getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).toList();
    }

    /**
     * Per-consultant registered work for a client in a single calendar month,
     * read off the work_full view (clientuuid + contract rate pre-resolved).
     * Returns hours (Σ workduration) and revenue (Σ workduration × rate),
     * one row per useruuid. Filters workduration > 0 (rate = 0 rows still
     * contribute hours, for the hours-fallback basis upstream).
     *
     * @param clientUuid resolved client uuid
     * @param year       calendar year of the period
     * @param month      calendar month (1–12)
     */
    @SuppressWarnings("unchecked")
    public List<ConsultantWorkRevenue> findRevenueByClientAndMonth(String clientUuid, int year, int month) {
        LocalDate periodStart = LocalDate.of(year, month, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);

        String sql = "SELECT wf.useruuid AS useruuid, " +
                "       SUM(wf.workduration)               AS hours, " +
                "       SUM(wf.workduration * wf.rate)     AS revenue " +
                "FROM work_full wf " +
                "WHERE wf.clientuuid = :clientUuid " +
                "  AND wf.registered >= :periodStart " +
                "  AND wf.registered <  :periodEnd " +
                "  AND wf.workduration > 0 " +
                "GROUP BY wf.useruuid";

        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("clientUuid", clientUuid)
                .setParameter("periodStart", periodStart)
                .setParameter("periodEnd", periodEnd)
                .getResultList()).stream()
                .map(t -> new ConsultantWorkRevenue(
                        t.get("useruuid", String.class),
                        toBigDecimal(t.get("hours")),
                        toBigDecimal(t.get("revenue"))))
                .toList();
    }

    private static java.math.BigDecimal toBigDecimal(Object o) {
        if (o == null) return java.math.BigDecimal.ZERO;
        if (o instanceof java.math.BigDecimal bd) return bd;
        return new java.math.BigDecimal(((Number) o).toString());
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
        q.setParameter("day", day);

        Object raw = q.getSingleResult();
        java.math.BigDecimal revenue = (raw == null)
                ? java.math.BigDecimal.ZERO
                : (raw instanceof java.math.BigDecimal)
                ? (java.math.BigDecimal) raw
                : new java.math.BigDecimal(raw.toString());

        return new DateValueDTO(day, revenue.doubleValue());
    }

    public List<WorkFull> findByPeriodAndProject(String fromdate, String todate, String projectuuid) {
        if(dateIt(todate).getDayOfMonth()>1) log.debug("Non-month-aligned period end (todate not the 1st): "+todate);
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
        if(todate.getDayOfMonth()>1) log.debug("Non-month-aligned period end (todate not the 1st): "+todate);
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

    @io.quarkus.cache.CacheResult(cacheName = "work-cache")
    public List<WorkFull> findByYearAndMonthAndProject(int year, int month, String projectuuid) {
        return findByPeriodAndProject(getFirstDayOfMonth(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), getFirstDayOfMonth(year, month).plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), projectuuid);
    }

    @io.quarkus.cache.CacheResult(cacheName = "work-cache")
    public Double findAmountUsedByContract(String contractuuid) {
        return findByContract(contractuuid).stream().mapToDouble(value -> value.getRate()*value.getWorkduration()).sum();
    }

    /**
     * Efficiently calculates the total registered amounts for multiple contracts in a single query.
     * This method uses an aggregate SQL query to calculate sums directly in the database,
     * which is much more efficient than fetching all records and calculating in memory.
     *
     * @param contractUuids List of contract UUIDs to calculate amounts for
     * @return Map of contract UUID to total registered amount
     */
    public Map<String, Double> findAmountsForContracts(List<String> contractUuids) {
        if (contractUuids == null || contractUuids.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Double> results = new HashMap<>();

        // Initialize all contracts with 0.0 to ensure all requested contracts are in the result
        for (String uuid : contractUuids) {
            results.put(uuid, 0.0);
        }

        // Use native query for efficient aggregation
        String sql = "SELECT contractuuid, SUM(rate * workduration) as amount " +
                     "FROM work_full " +
                     "WHERE contractuuid IN (:uuids) " +
                     "GROUP BY contractuuid";

        @SuppressWarnings("unchecked")
        List<Object[]> queryResults = em.createNativeQuery(sql)
                .setParameter("uuids", contractUuids)
                .getResultList();

        // Map results
        for (Object[] row : queryResults) {
            String contractUuid = (String) row[0];
            Double amount = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            results.put(contractUuid, amount);
        }

        return results;
    }



    /**
     * Idempotent save for POST /work. The stored row is unique on {@code uq_work_user_date_task}
     * = (useruuid, registered, taskuuid); the frontend re-submits the same (user, date, task) row on
     * double-click / retry / concurrent tabs, so a save must converge on "one row for the tuple"
     * rather than 500.
     *
     * <p>The pre-insert lookup in {@link #persistOrUpdateInTx(Work)} turns the common (serialized)
     * re-submit into an in-place update. But two genuinely concurrent requests can both pass that
     * lookup — each JTA transaction has its own MariaDB REPEATABLE READ snapshot, so neither sees the
     * other's not-yet-committed insert — and the loser's {@code persistAndFlush()} then trips the
     * unique key. We swallow only that specific duplicate-key {@link PersistenceException} and retry
     * the unit of work <b>once</b> in a fresh transaction (via {@link #self}); its new snapshot now
     * sees the winning row and takes the update path, so a duplicate submit is a 2xx success rather
     * than a 500. A second consecutive failure (should not happen) is left to propagate — the
     * flushed violation is an unchecked {@link PersistenceException}, mapped to a clean 409 by
     * {@code DatabaseConstraintViolationExceptionMapper}, never a 500.
     */
    public void persistOrUpdate(Work work) {
        try {
            self.persistOrUpdateInTx(work);
        } catch (PersistenceException e) {
            if (!isDuplicateWorkKeyViolation(e)) {
                throw e;
            }
            log.warnf("Concurrent duplicate work insert (registered=%s, userUuid=%s, taskUuid=%s) — reconciling idempotently in a fresh transaction",
                    work.getRegistered(), work.getUseruuid(), work.getTaskuuid());
            self.persistOrUpdateInTx(work);
        }
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "work-cache")
    @CacheInvalidateAll(cacheName = "employee-availability")
    public void persistOrUpdateInTx(Work work) {
        log.debugf("persistOrUpdate called: userUuid=%s, taskUuid=%s, registered=%s, duration=%.2f, rate=%.2f",
                work.getUseruuid(), work.getTaskuuid(), work.getRegistered(), work.getWorkduration(), work.getRate());
        List<Work> workList = findExistingWork(work.getRegistered(), work.getUseruuid(), work.getTaskuuid());
        if(!workList.isEmpty()) {
            Work existing = workList.get(0);
            if(existing.isPaidOut()) {
                log.warnf("Skipping update for already paid-out work: workUuid=%s, userUuid=%s, taskUuid=%s, registered=%s",
                        existing.getUuid(), work.getUseruuid(), work.getTaskuuid(), work.getRegistered());
                return;
            }
            work.setUuid(existing.getUuid());
            updateExistingWork(work);
            log.infof("Work updated: workUuid=%s, userUuid=%s, taskUuid=%s, registered=%s, duration=%.2f, rate=%.2f",
                    work.getUuid(), work.getUseruuid(), work.getTaskuuid(), work.getRegistered(), work.getWorkduration(), work.getRate());
        } else {
            work.setUuid(UUID.randomUUID().toString());
            // persistAndFlush (not persist) so a concurrent-insert race trips uq_work_user_date_task
            // HERE, at flush, as an unchecked PersistenceException the caller can catch and retry —
            // instead of a deferred commit-time RollbackException that Arc rewraps as
            // ArcUndeclaredThrowableException and GenericExceptionMapper turns into a 500.
            work.persistAndFlush();
            log.infof("Work created: workUuid=%s, userUuid=%s, taskUuid=%s, registered=%s, duration=%.2f, rate=%.2f",
                    work.getUuid(), work.getUseruuid(), work.getTaskuuid(), work.getRegistered(), work.getWorkduration(), work.getRate());
        }
    }

    /**
     * Panache lookup for an existing row on the {@code uq_work_user_date_task} tuple
     * (registered, useruuid, taskuuid). Package-private seam so the idempotency decision in
     * {@link #persistOrUpdateInTx(Work)} can be unit-tested without a live database.
     */
    List<Work> findExistingWork(LocalDate registered, String useruuid, String taskuuid) {
        return Work.find("registered = ?1 AND useruuid LIKE ?2 AND taskuuid LIKE ?3", registered, useruuid, taskuuid).list();
    }

    /**
     * Bulk update of the existing row for the tuple. Package-private seam so
     * {@link #persistOrUpdateInTx(Work)} can be unit-tested without a live database.
     */
    void updateExistingWork(Work work) {
        Work.update("workduration = ?1, comments = ?2, paidOut = ?3 WHERE registered = ?4 AND useruuid LIKE ?5 AND taskuuid LIKE ?6",
                work.getWorkduration(), work.getComments(), work.getPaidOut(), work.getRegistered(), work.getUseruuid(), work.getTaskuuid());
    }

    /**
     * True only for a duplicate on the {@code uq_work_user_date_task} unique key. Scoped to that
     * one constraint so we retry a lost insert race but never loop on an unrelated violation
     * (e.g. a foreign key), which is left to surface as a 409.
     */
    private static boolean isDuplicateWorkKeyViolation(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase().contains("uq_work_user_date_task")) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains("uq_work_user_date_task")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        log.infof("Work marked as paid: workUuid=%s, userUuid=%s, taskUuid=%s, registered=%s, duration=%.2f, rate=%.2f, paidOut=%s",
                work.getUuid(), work.getUseruuid(), work.getTaskuuid(), work.getRegistered(),
                work.getWorkduration(), work.getRate(), work.getPaidOut());
    }

    @Transactional
    public void clearPaidAndUpdate(Work work) {
        log.infof("Work paid-out cleared: workUuid=%s, userUuid=%s, taskUuid=%s, registered=%s, previousPaidOut=%s",
                work.getUuid(), work.getUseruuid(), work.getTaskuuid(), work.getRegistered(), work.getPaidOut());
        work.setPaidOut(null);
        Work.update("paidOut = ?1 WHERE uuid like ?2 ", work.getPaidOut(), work.getUuid());
    }

    /**
     * REQUIRES_NEW is load-bearing: the only caller is InvoiceBookedPayoutObserver, an
     * {@code @Observes(during = AFTER_SUCCESS)} observer that runs in the afterCompletion
     * callback of the just-committed booking transaction. That completed transaction is still
     * associated with the thread, so the default {@code REQUIRED} joins it and Narayana rejects
     * the write with {@code InactiveTransactionException}. REQUIRES_NEW suspends the completed
     * transaction and begins a fresh one.
     *
     * <p>Idempotent: only rows with {@code paid_out IS NULL} are stamped, so a re-run (manual
     * backfill or a re-fired event) never overwrites an earlier payout timestamp.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void registerAsPaidout(String contractuuid, String projectuuid, int month, int year) {
        log.infof("Batch paidout registration started: contractUuid=%s, projectUuid=%s, month=%d, year=%d",
                contractuuid, projectuuid, month, year);
        LocalDateTime now = LocalDateTime.now();
        long count = 0;
        List<WorkFull> workItems = WorkFull.<WorkFull>find("contractuuid = ?1 AND projectuuid = ?2 AND MONTH(registered) = ?3 AND YEAR(registered) = ?4", contractuuid, projectuuid, month, year).list();
        for (WorkFull work : workItems) {
            count += Work.update("paidOut = ?1 WHERE uuid = ?2 AND paidOut IS NULL", now, work.getUuid());
        }
        log.infof("Batch paidout registration completed: contractUuid=%s, projectUuid=%s, month=%d, year=%d, workItems=%d, newlyMarked=%d, paidOutTimestamp=%s",
                contractuuid, projectuuid, month, year, workItems.size(), count, now);
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
