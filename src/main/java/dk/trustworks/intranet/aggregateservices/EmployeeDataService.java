package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.model.EmployeeAggregateData;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeBudgetPerDay;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeDataPerMonth;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.UserFinanceDocument;
import dk.trustworks.intranet.userservice.dto.Capacity;
import dk.trustworks.intranet.userservice.model.Team;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.services.CapacityService;
import dk.trustworks.intranet.userservice.services.TeamRoleService;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JBossLog
@ApplicationScoped
public class EmployeeDataService {

    private final String sickness = "02bf71c5-f588-46cf-9695-5864020eb1c4";
    private final String vacation = "f585f46f-19c1-4a3a-9ebd-1a4f21007282";
    private final String maternity = "da2f89fc-9aef-4029-8ac2-7486be60e9b9";

    private final LocalDate startDate = LocalDate.of(2014, 2, 1);

    //private final Map<String, Map<LocalDate, EmployeeAggregateData>> dataMap = Collections.synchronizedMap(new HashMap<>());

    @Inject
    EventBus bus;

    @PersistenceContext
    EntityManager entityManager;

    @Inject
    WorkService workService;

    @Inject
    FinanceService financeService;

    @Inject
    UserService userService;

    @Inject
    TeamRoleService teamRoleService;

    @Inject
    CapacityService capacityService;

    @Inject
    TeamService teamService;

    @Inject
    BudgetService budgetService;

    @Inject
    TransactionManager tm;

    private final List<LocalDate> reloadMonthRevenueDates = Collections.synchronizedList(new ArrayList<>());

    /**
     * Set the following parameters:
     * <p>
     * RegisteredHours
     * RegisteredAmount
     * HelpedCollegueHours
     * GotHelpByCollegueHours
     *
     * @param refreshDate
     * @param user
     * @param employeeAggregateData
     */
    public void updateWorkData(LocalDate refreshDate, User user, EmployeeAggregateData employeeAggregateData) {
        employeeAggregateData.setRegisteredHours(0);
        employeeAggregateData.setRegisteredAmount(0);
        employeeAggregateData.setHelpedColleagueHours(0);
        employeeAggregateData.setGotHelpByColleagueHours(0);

        for (WorkFull work : workService.findByPeriodAndUserUUID(refreshDate, refreshDate.plusMonths(1), user.getUuid())) {
            if (work.getRegistered().getYear() != refreshDate.getYear() && work.getRegistered().getMonthValue() != refreshDate.getMonthValue())
                continue;
            //EmployeeAggregateData employeeAggregateData = getEmployeeAggregateData(refreshDate, work.getUseruuid());

            // Tilføj sygdom, ferie og barsel
            switch (work.getTaskuuid()) {
                case sickness -> employeeAggregateData.addSickdays(work.getWorkduration());
                case vacation -> employeeAggregateData.addVacation(work.getWorkduration());
                case maternity -> employeeAggregateData.addMaternity(work.getWorkduration());
            }

            // Tilføj fakturerbart arbejde
            if (work.getRate() == 0.0) continue;
            employeeAggregateData.addWorkDuration(work.getWorkduration());
            employeeAggregateData.addRegisteredAmount(work.getWorkduration() * work.getRate());

            // add help colleague
            if (work.getWorkas() != null && !work.getWorkas().isBlank()) {
                //EmployeeAggregateData employeeAggregateDataHelped = getEmployeeAggregateData(refreshDate, work.getUseruuid());
                //employeeAggregateDataHelped.addHelpedColleagueHours(work.getWorkduration());
                //EmployeeAggregateData employeeAggregateDataGotHelpBy = getEmployeeAggregateData(refreshDate, work.getWorkas());
                //employeeAggregateDataGotHelpBy.addGotHelpByColleagueHours(work.getWorkduration());
            }
        }
    }

    /**
     * Set the following parameters
     * <p>
     * BudgetHours
     * BudgetAmount
     *
     * @param refreshDate
     * @param user
     * @param employeeAggregateData
     */
    public void updateBudgetData(LocalDate refreshDate, User user, EmployeeAggregateData employeeAggregateData) {
        employeeAggregateData.setBudgetHours(0);
        employeeAggregateData.setBudgetAmount(0);
        employeeAggregateData.setBudgetHoursWithNoAvailabilityAdjustment(0);
        //employeeAggregateData.setBudgetDocuments(new ArrayList<>());

        List<EmployeeBudgetPerDay> employeeBudgetPerDays = budgetService.getConsultantBudgetDataByMonth(user.getUuid(), refreshDate);

        for (EmployeeBudgetPerDay employeeBudgetPerDay : employeeBudgetPerDays) {
            //EmployeeAggregateData employeeAggregateData = getEmployeeAggregateData(budgetDocument.getMonth(), budgetDocument.getUser().getUuid());
            employeeAggregateData.addBudgetHours(employeeBudgetPerDay.getBudgetHours());
            employeeAggregateData.addBudgetHoursWithNoAvailabilityAdjustment(employeeBudgetPerDay.getBudgetHoursWithNoAvailabilityAdjustment());
            employeeAggregateData.addBudgetAmount(employeeBudgetPerDay.getBudgetHours()* employeeBudgetPerDay.getRate());
            //employeeAggregateData.addBudgetDocument(budgetDocument);
        }
    }

    /**
     * Set the following parameters:
     * <p>
     * StatusType
     *
     * @param refreshDate
     * @param user
     * @param employeeAggregateData
     */
    public void updateMetaData(LocalDate refreshDate, User user, EmployeeAggregateData employeeAggregateData) {
        //addMissingUsersInMap(refreshDate);
        //for (User user : userService.listAll(false)) {
            //EmployeeAggregateData employeeAggregateData = getEmployeeAggregateData(refreshDate, user.getUuid());
            UserStatus userStatus = userService.getUserStatus(user, refreshDate);
            employeeAggregateData.setStatusType(userStatus.getStatus());
            employeeAggregateData.setConsultantType(userStatus.getType());
        //}
    }

    public void updateFinanceData(LocalDate refreshDate, User user, EmployeeAggregateData employeeAggregateData) {
        //addMissingUsersInMap(refreshDate);
        List<UserFinanceDocument> financeDocuments = financeService.getFinanceDataForSingleMonth(refreshDate).stream().filter(userFinanceDocument -> userFinanceDocument.getUser().getUuid().equals(user.getUuid())).toList();

        for (UserFinanceDocument financeDocument : financeDocuments) {
            //EmployeeAggregateData employeeAggregateData = getEmployeeAggregateData(refreshDate, financeDocument.getUser().getUuid());
            employeeAggregateData.setSharedExpenses(financeDocument.getSharedExpense() + financeDocument.getPersonaleExpense());
            employeeAggregateData.setSalary(financeDocument.getSalary());
        }
    }

    public void updateAvailabilityData(LocalDate refreshDate, User user, EmployeeAggregateData employeeAggregateData) {
        //addMissingUsersInMap(refreshDate);

        Capacity capacity = capacityService.calculateCapacityByMonthByUser(user.getUuid(), refreshDate);
        employeeAggregateData.setAvailableHours(capacity.getTotalAllocation());
    }

    public void updateTeamData(LocalDate refreshDate, User user, EmployeeAggregateData employeeAggregateData) {
        //addMissingUsersInMap(refreshDate);
        List<Team> teams = teamService.listAll();

        //for (User user : userService.findEmployedUsersByDate(refreshDate, true, ConsultantType.CONSULTANT, ConsultantType.STUDENT, ConsultantType.STAFF)) {
            //EmployeeAggregateData employeeAggregateData = getEmployeeAggregateData(refreshDate, user.getUuid());
        /*
        employeeAggregateData.setTeamMemberOf("");
        employeeAggregateData.getTeamLeaderOf().clear();
        employeeAggregateData.getTeamSponsorOf().clear();
        employeeAggregateData.getTeamGuestOf().clear();

         */
        for (TeamRole userTeamRole : teamRoleService.listAll(user.getUuid())) {
            if(DateUtils.isBetween(refreshDate, userTeamRole.getStartdate(), (userTeamRole.getEnddate()!=null?userTeamRole.getEnddate():LocalDate.now()))) {
                if(userTeamRole.getTeammembertype()==null) {
                    log.error("TeamRole "+userTeamRole+" for user "+user.getUsername()+" has no team role type");
                    continue;
                }
                switch (userTeamRole.getTeammembertype()) {
                    case MEMBER ->
                            employeeAggregateData.setTeamMemberOf(String.join(",", teams.stream().map(Team::getUuid).filter(uuid -> uuid.equals(userTeamRole.getTeamuuid())).toList()));
                    case LEADER ->
                            employeeAggregateData.setTeamLeaderOf(String.join(",", teams.stream().map(Team::getUuid).filter(uuid -> uuid.equals(userTeamRole.getTeamuuid())).toList()));
                    case SPONSOR ->
                            employeeAggregateData.setTeamSponsorOf(String.join(",", teams.stream().map(Team::getUuid).filter(uuid -> uuid.equals(userTeamRole.getTeamuuid())).toList()));
                    case GUEST ->
                            employeeAggregateData.setTeamGuestOf(String.join(",", teams.stream().map(Team::getUuid).filter(uuid -> uuid.equals(userTeamRole.getTeamuuid())).toList()));
                }
            }
        }
    }

    @Transactional
    //@ConsumeEvent(value = "process-employee-data-consumer", blocking = true)
    //(rate = 10, rateUnit = TimeUnit.SECONDS)
    public void process(AbstractMap.SimpleEntry<LocalDate, User> simpleEntry) {
        try {
            LocalDate reloadDate = simpleEntry.getKey();
            User user = simpleEntry.getValue();
            EmployeeAggregateData data = new EmployeeAggregateData(reloadDate, user.getUuid());
            //QuarkusTransaction.run( () -> {
            //log.info("EmployeeDataService: Updating cached data for (" + user.getUsername() + "): " + reloadDate);
            updateWorkData(reloadDate, user, data);
            updateFinanceData(reloadDate, user, data);
            updateAvailabilityData(reloadDate, user, data);
            updateBudgetData(reloadDate, user, data);
            updateTeamData(reloadDate, user, data);
            updateMetaData(reloadDate, user, data);
            data.updateCalculatedData();
            //System.out.println("data = " + data);
            data.persist();
        } catch (Exception e) {
            e.printStackTrace();
        }
        //});
    }

    //@Scheduled(every = "24h", identity = "UpdateEmployeeData")
    //@Scheduled(cron="0 0 1 * * ?")
    //@Scheduled(every = "6h", delay = 5, identity = "UpdateEmployeeData")
    //@Transactional
    public void updateAllData() {
        log.debug("EmployeeDataService.updateAllData...STARTED!");
        LocalDate lookupDate = startDate;
        do {
            reloadMonthRevenueDates.add(lookupDate);
            lookupDate = lookupDate.plusMonths(1);
        } while (lookupDate.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));

        long l = System.currentTimeMillis();

        QuarkusTransaction.requiringNew().run(() -> {
            String sql = "TRUNCATE TABLE employee_data";
            Query query = entityManager.createNativeQuery(sql);
            query.executeUpdate();
        });
        //QuarkusTransaction.run(() -> EmployeeAggregateData.deleteAll());
        userService.listAll(false).forEach(user -> {
            reloadMonthRevenueDates.forEach(reloadDate -> {
                QuarkusTransaction.requiringNew().run(() -> {
                    process(new AbstractMap.SimpleEntry<>(reloadDate, user));
                });
                //bus.<String>send("process-employee-data-consumer", new AbstractMap.SimpleEntry<>(reloadDate, user));
                //if(reloadDate.equals(LocalDate.of(2022,11,1))) System.out.println("Sending: "+reloadDate+": "+user.getUsername());

                //QuarkusTransaction.commit();
                    //QuarkusTransaction.commit();
                //QuarkusTransaction.run(QuarkusTransaction.runOptions().semantic(RunOptions.Semantic.REQUIRE_NEW), data::persistAndFlush);
            });
        });

        reloadMonthRevenueDates.clear();
        log.debug("EmployeeDataService.updateAllData...DONE!");
        log.debug("EmployeeDataService Time: "+(System.currentTimeMillis()-l));
    }

    //@PostConstruct
    public void init() {
        log.debug("EmployeeDataService.init");
        LocalDate lookupDate = startDate;
        do {
            reloadMonthRevenueDates.add(lookupDate);
            lookupDate = lookupDate.plusMonths(1);
        } while (lookupDate.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));

    }

    /*
    public List<EmployeeAggregateData> getDataMap() {
        return dataMap.values().stream().flatMap(map -> map.values().stream()).collect(Collectors.toList());
    }
     */

    public List<EmployeeAggregateData> getDataMap(LocalDate fromdate, LocalDate todate) {
        return EmployeeAggregateData.find("month >= ?1 and month < ?2", fromdate, todate).list();
    }

    public List<EmployeeDataPerMonth> getAllEmployeeDataPerMonth(LocalDate fromdate, LocalDate todate) {
        return EmployeeDataPerMonth.list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') " +
                "      BETWEEN STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') " +
                "      AND STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d')", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue());
    }


    @CacheResult(cacheName = "employee-data-per-month-cache")
    public List<EmployeeDataPerMonth> getAllEmployeeDataPerMonth(@CacheKey String companyuuid, @CacheKey LocalDate fromdate, @CacheKey LocalDate todate) {
        Query nativeQuery = entityManager.createNativeQuery("SELECT " +
                "    ad_agg.id, " +
                "    ad_agg.useruuid, " +
                "    ad_agg.consultant_type, " +
                "    ad_agg.status_type, " +
                "    ad_agg.companyuuid, " +
                "    ad_agg.year, " +
                "    ad_agg.month, " +
                "    ad_agg.gross_available_hours, " +
                "    ad_agg.unavailable_hours, " +
                "    ad_agg.vacation_hours, " +
                "    ad_agg.sick_hours, " +
                "    ad_agg.maternity_leave_hours, " +
                "    ad_agg.non_payd_leave_hours, " +
                "    ad_agg.paid_leave_hours, " +
                "    COALESCE(ww.workduration, 0) AS registered_billable_hours, " +
                "    COALESCE(ww.total_billed, 0) AS registered_amount, " +
                "    COALESCE(bb.budgetHours, 0) AS budget_hours, " +
                "    ad_agg.avg_salary, " +
                "    ad_agg.is_tw_bonus_eligible " +
                "FROM ( " +
                "    SELECT " +
                "        MIN(ad.id) AS id, " +
                "        ad.useruuid, " +
                "        ad.consultant_type, " +
                "        ad.status_type, " +
                "        ad.companyuuid, " +
                "        ad.year, " +
                "        ad.month, " +
                "        SUM(ad.gross_available_hours) AS gross_available_hours, " +
                "        SUM(ad.unavailable_hours) AS unavailable_hours, " +
                "        SUM(ad.vacation_hours) AS vacation_hours, " +
                "        SUM(ad.sick_hours) AS sick_hours, " +
                "        SUM(ad.maternity_leave_hours) AS maternity_leave_hours, " +
                "        SUM(ad.non_payd_leave_hours) AS non_payd_leave_hours, " +
                "        SUM(ad.paid_leave_hours) AS paid_leave_hours, " +
                "        AVG(ad.salary) AS avg_salary, " +
                "        MAX(ad.is_tw_bonus_eligible) AS is_tw_bonus_eligible " +
                "    FROM twservices.availability_document ad " +
                "    WHERE companyuuid = :companyuuid and document_date >= :startDate and document_date < :endDate " +
                "    GROUP BY ad.useruuid, ad.year, ad.month, ad.companyuuid " +
                ") ad_agg " +
                "LEFT JOIN ( " +
                "    SELECT " +
                "        w.useruuid, " +
                "        YEAR(w.registered) AS year, " +
                "        MONTH(w.registered) AS month, " +
                "        SUM(w.workduration) AS workduration, " +
                "        SUM(IFNULL(w.rate, 0) * w.workduration) AS total_billed " +
                "    FROM twservices.work_full w " +
                "    WHERE w.rate > 0 and consultant_company_uuid = :companyuuid and registered >= :startDate and registered < :endDate " +
                "    GROUP BY w.useruuid, YEAR(w.registered), MONTH(w.registered) " +
                ") ww ON ww.year = ad_agg.year AND ww.month = ad_agg.month AND ww.useruuid = ad_agg.useruuid " +
                "LEFT JOIN ( " +
                "    select " +
                "       `ad`.`useruuid`, " +
                "       `ad`.`year`             AS `year`, " +
                "       `ad`.`month`            AS `month`, " +
                "       sum(`ad`.`budgetHours`) AS `budgetHours` " +
                "from `twservices`.`budget_document` `ad` " +
                "where companyuuid = :companyuuid and document_date >= :startDate and document_date < :endDate " +
                "group by `ad`.`useruuid`, `ad`.`year`, `ad`.`month` " +
                ") bb ON bb.year = ad_agg.year AND bb.month = ad_agg.month AND bb.useruuid = ad_agg.useruuid " +
                "ORDER BY ad_agg.year, ad_agg.month;", EmployeeDataPerMonth.class);
        nativeQuery.setParameter("companyuuid", companyuuid);
        nativeQuery.setParameter("startDate", fromdate);
        nativeQuery.setParameter("endDate", todate);
        return nativeQuery.getResultList();
    }

    public List<EmployeeDataPerMonth> getEmployeeDataPerMonth(String useruuid, LocalDate fromdate, LocalDate todate) {
        Query nativeQuery = entityManager.createNativeQuery("SELECT " +
                "    ad_agg.id, " +
                "    ad_agg.useruuid, " +
                "    ad_agg.consultant_type, " +
                "    ad_agg.status_type, " +
                "    ad_agg.companyuuid, " +
                "    ad_agg.year, " +
                "    ad_agg.month, " +
                "    ad_agg.gross_available_hours, " +
                "    ad_agg.unavailable_hours, " +
                "    ad_agg.vacation_hours, " +
                "    ad_agg.sick_hours, " +
                "    ad_agg.maternity_leave_hours, " +
                "    ad_agg.non_payd_leave_hours, " +
                "    ad_agg.paid_leave_hours, " +
                "    COALESCE(ww.workduration, 0) AS registered_billable_hours, " +
                "    COALESCE(ww.total_billed, 0) AS registered_amount, " +
                "    COALESCE(bb.budgetHours, 0) AS budget_hours, " +
                "    ad_agg.avg_salary, " +
                "    ad_agg.is_tw_bonus_eligible " +
                "FROM ( " +
                "    SELECT " +
                "        MIN(ad.id) AS id, " +
                "        ad.useruuid, " +
                "        ad.consultant_type, " +
                "        ad.status_type, " +
                "        ad.companyuuid, " +
                "        ad.year, " +
                "        ad.month, " +
                "        SUM(ad.gross_available_hours) AS gross_available_hours, " +
                "        SUM(ad.unavailable_hours) AS unavailable_hours, " +
                "        SUM(ad.vacation_hours) AS vacation_hours, " +
                "        SUM(ad.sick_hours) AS sick_hours, " +
                "        SUM(ad.maternity_leave_hours) AS maternity_leave_hours, " +
                "        SUM(ad.non_payd_leave_hours) AS non_payd_leave_hours, " +
                "        SUM(ad.paid_leave_hours) AS paid_leave_hours, " +
                "        AVG(ad.salary) AS avg_salary, " +
                "        MAX(ad.is_tw_bonus_eligible) AS is_tw_bonus_eligible " +
                "    FROM twservices.availability_document ad " +
                "    WHERE useruuid = :useruuid and document_date >= :startDate and document_date < :endDate " +
                "    GROUP BY ad.useruuid, ad.year, ad.month, ad.companyuuid " +
                ") ad_agg " +
                "LEFT JOIN ( " +
                "    SELECT " +
                "        w.useruuid, " +
                "        YEAR(w.registered) AS year, " +
                "        MONTH(w.registered) AS month, " +
                "        SUM(w.workduration) AS workduration, " +
                "        SUM(IFNULL(w.rate, 0) * w.workduration) AS total_billed " +
                "    FROM twservices.work_full w " +
                "    WHERE w.rate > 0 and useruuid = :useruuid and registered >= :startDate and registered < :endDate " +
                "    GROUP BY w.useruuid, YEAR(w.registered), MONTH(w.registered) " +
                ") ww ON ww.year = ad_agg.year AND ww.month = ad_agg.month AND ww.useruuid = ad_agg.useruuid " +
                "LEFT JOIN ( " +
                "    select " +
                "       `ad`.`useruuid`, " +
                "       `ad`.`year`             AS `year`, " +
                "       `ad`.`month`            AS `month`, " +
                "       sum(`ad`.`budgetHours`) AS `budgetHours` " +
                "from `twservices`.`budget_document` `ad` " +
                "where useruuid = :useruuid and document_date >= :startDate and document_date < :endDate " +
                "group by `ad`.`useruuid`, `ad`.`year`, `ad`.`month` " +
                ") bb ON bb.year = ad_agg.year AND bb.month = ad_agg.month AND bb.useruuid = ad_agg.useruuid " +
                "ORDER BY ad_agg.year, ad_agg.month;", EmployeeDataPerMonth.class);
        nativeQuery.setParameter("useruuid", useruuid);
        nativeQuery.setParameter("startDate", fromdate);
        nativeQuery.setParameter("endDate", todate);
        return nativeQuery.getResultList();
    }
}


