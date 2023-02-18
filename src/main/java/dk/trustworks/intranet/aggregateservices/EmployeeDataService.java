package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.bi.model.EmployeeAggregateData;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.dto.UserFinanceDocument;
import dk.trustworks.intranet.userservice.dto.Capacity;
import dk.trustworks.intranet.userservice.model.Team;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.userservice.services.CapacityService;
import dk.trustworks.intranet.userservice.services.TeamRoleService;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.userservice.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.TransactionManager;
import javax.transaction.Transactional;
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

        List<BudgetDocument> budgetDocuments = budgetService.getConsultantBudgetDataByMonth(user.getUuid(), refreshDate);

        for (BudgetDocument budgetDocument : budgetDocuments) {
            //EmployeeAggregateData employeeAggregateData = getEmployeeAggregateData(budgetDocument.getMonth(), budgetDocument.getUser().getUuid());
            employeeAggregateData.addBudgetHours(budgetDocument.getBudgetHours());
            employeeAggregateData.addBudgetHoursWithNoAvailabilityAdjustment(budgetDocument.getBudgetHoursWithNoAvailabilityAdjustment());
            employeeAggregateData.addBudgetAmount(budgetDocument.getBudgetHours()*budgetDocument.getRate());
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
    @ConsumeEvent(value = "process-employee-data-consumer", blocking = true)
    //@Throttled(rate = 10, rateUnit = TimeUnit.SECONDS)
    public void process(AbstractMap.SimpleEntry<LocalDate, User> simpleEntry) {
        LocalDate reloadDate = simpleEntry.getKey();
        User user = simpleEntry.getValue();
        EmployeeAggregateData data = new EmployeeAggregateData(reloadDate, user.getUuid());
        //QuarkusTransaction.run( () -> {
            log.info("EmployeeDataService: Updating cached data for (" + user.getUsername() + "): " + reloadDate);
            updateWorkData(reloadDate, user, data);
            updateFinanceData(reloadDate, user, data);
            updateAvailabilityData(reloadDate, user, data);
            updateBudgetData(reloadDate, user, data);
            updateTeamData(reloadDate, user, data);
            updateMetaData(reloadDate, user, data);
            data.persistAndFlush();
        //});
    }

    //@Scheduled(every = "24h", identity = "UpdateEmployeeData", delay = 30)
    @Scheduled(cron = "0 0 1 * * ?")
    //@Transactional
    public void updateAllData() {
        log.debug("EmployeeDataService.updateAllData...STARTED!");
        LocalDate lookupDate = startDate;
        do {
            reloadMonthRevenueDates.add(lookupDate);
            lookupDate = lookupDate.plusMonths(1);
        } while (lookupDate.isBefore(DateUtils.getCurrentFiscalStartDate().plusYears(2)));

        long l = System.currentTimeMillis();
        QuarkusTransaction.run(() -> EmployeeAggregateData.deleteAll());
        userService.listAll(false).forEach(user -> {
            reloadMonthRevenueDates.forEach(reloadDate -> {
                bus.<String>requestAndForget("process-employee-data-consumer", new AbstractMap.SimpleEntry<>(reloadDate, user));


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


    //private EmployeeAggregateData getEmployeeAggregateData(LocalDate refreshDate, String uuid) {
        //return dataMap.get(uuid).get(refreshDate);
    //}

}


