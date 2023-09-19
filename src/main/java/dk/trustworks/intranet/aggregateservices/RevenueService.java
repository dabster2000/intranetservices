package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.FinanceDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.invoiceservice.services.InvoiceService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.NumberUtils;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.ColumnResult;
import javax.persistence.ConstructorResult;
import javax.persistence.EntityManager;
import javax.persistence.SqlResultSetMapping;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@ApplicationScoped
@SqlResultSetMapping(
        name="KeyValueDTOResult",
        classes={
                @ConstructorResult(
                        targetClass=KeyValueDTO.class,
                        columns={
                                @ColumnResult(name="clientuuid"),
                                @ColumnResult(name="amount")
                        }
                )
        }
)
public class RevenueService {

    @Inject
    EntityManager em;

    @Inject
    UserService userService;

    @Inject
    WorkService workService;

    @Inject
    ClientService clientService;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    FinanceService financeService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    SalaryService salaryService;

    @Inject
    TeamService teamService;

    public double getRegisteredRevenueForSingleMonth(LocalDate month) {
        return workService.getRegisteredRevenueForSingleMonth(month);
    }

    public List<KeyValueDTO> getRegisteredRevenuePerClient(List<String> clientuuids) {
        List<Object[]> resultList = em.createNativeQuery(
                "select w.clientuuid clientuuid, sum(w.rate * w.workduration) as amount from work_full w " +
                "where registered >= '2021-07-01' and registered < '2022-07-01' and clientuuid in ('" + String.join("','", clientuuids) + "') group by w.clientuuid").getResultList();
        List<KeyValueDTO> result = new ArrayList<>();
        for (Object[] objects : resultList) {
            result.add(new KeyValueDTO((String)objects[0], ""+objects[1]));
        }
        return result;
    }

    public List<GraphKeyValue> getSumOfRegisteredRevenueByClient() {
        Map<String, GraphKeyValue> clientRevenueMap = new HashMap<>();
        List<Client> clients = clientService.listAllClients();
        List<KeyValueDTO> keyValueDTOS = getRegisteredRevenuePerClient(clients.stream().map(Client::getUuid).collect(Collectors.toList()));
        for (KeyValueDTO keyValueDTO : keyValueDTOS) {
            String key = keyValueDTO.getKey();
            clientRevenueMap.putIfAbsent(key, new GraphKeyValue(key, clients.stream().filter(client -> client.getUuid().equalsIgnoreCase(key)).findFirst().orElse(new Client()).getName(), 0));
            clientRevenueMap.get(key).setValue(clientRevenueMap.get(key).getValue()+Double.parseDouble(keyValueDTO.getValue()));
        }
        return List.copyOf(clientRevenueMap.values());
    }

    public List<GraphKeyValue> getSumOfRegisteredRevenueByClientByFiscalYear(int fiscalYear) {
        LocalDate fiscalYearDate = DateUtils.getCurrentFiscalStartDate().withYear(fiscalYear);
        Map<String, GraphKeyValue> clientRevenueMap = new HashMap<>();
        Map<String, Double> workList = workService.findByPeriod(fiscalYearDate, fiscalYearDate.plusYears(1)).stream()
                .filter(work -> work.getRate()>0)
                .collect(Collectors.groupingBy(WorkFull::getClientuuid, Collectors.summingDouble(work -> work.getWorkduration()*work.getRate())));
        return workList.keySet().stream().map(s -> new GraphKeyValue(s, "", workList.get(s))).collect(Collectors.toList());
    }

    public List<GraphKeyValue> getRegisteredHoursPerConsultantForSingleMonth(LocalDate month) {
        return workService.getRegisteredHoursPerConsultantForSingleMonth(month);
    }

    public double getRegisteredHoursForSingleMonthAndSingleConsultant(String useruuid, LocalDate month) {
        return workService.getRegisteredHoursForSingleMonthAndSingleConsultant(useruuid, month);
    }

    public double getRegisteredRevenueForSingleMonthAndSingleConsultant(String useruuid, LocalDate month) {
        return workService.getRegisteredRevenueForSingleMonthAndSingleConsultant(useruuid, month);
        /*
        List<WorkDocument> workDocumentList = getCachedData();
        return workDocumentList.stream()
                .filter(workDocument -> (workDocument.getUser().getUuid().equals(useruuid) && workDocument.getMonth().isEqual(month.withDayOfMonth(1))))
                .mapToDouble(workDocument -> workDocument.getRate() * workDocument.getWorkHours()).sum();
         */
    }

    public GraphKeyValue getTotalTeamProfits(LocalDate fiscalYear, List<String> teams) {
        return getTotalTeamProfits(fiscalYear, fiscalYear.plusYears(1).minusMonths(1), teams);
    }

    public GraphKeyValue getTotalTeamProfits(LocalDate fromdate, LocalDate todate, List<String> teams) {
        // Find de konsulenter der har været i de respektive teams i den angivne periode. Filtrer eventuelle dubletter.
        Map<String, User> allConsultantsInPeriod = new HashMap<>();
        for (String team : teams) {
            for (int i = 0; i < DateUtils.countMonthsBetween(fromdate, todate); i++) {
                LocalDate date = fromdate.plusMonths(i);
                teamService.getUsersByTeam(team, date).forEach(user -> allConsultantsInPeriod.put(user.getUuid(), user));
            }
        }

        // Indhent revenue for alle konsulenterne og lav en sum over hele perioden.
        double consultantRevenue = 0.0;
        log.info("Consultant revenue:");
        for (User user : allConsultantsInPeriod.values()) {
            double singleConsultantRevenue = getRegisteredRevenueByPeriodAndSingleConsultant(user.getUuid(), stringIt(fromdate), stringIt(todate)).values().stream().mapToDouble(value -> value).sum();
            consultantRevenue += singleConsultantRevenue;
            log.info(user.getUsername()+": "+singleConsultantRevenue);
        }

        log.info("All consultants revenue: "+consultantRevenue);

        double sumExpenses = financeService.getUserFinanceData().stream().sorted(Comparator.comparing(o -> o.getUser().getUsername()))
                .filter(ufd -> (ufd.getMonth().isAfter(fromdate.minusMonths(1)) &&
                        ufd.getMonth().isBefore(todate.plusMonths(1)) &&
                        allConsultantsInPeriod.values().stream().anyMatch(user -> user.getUuid().equals(ufd.getUser().getUuid()))))
                .peek(u -> System.out.println(u.getUser().getUsername()+" ("+ stringIt(u.getMonth())+"): "+u.getSalary()+", "+u.getSharedExpense())).mapToDouble(value -> value.getSalary() + value.getSharedExpense()).sum();

        log.info("All consultant expenses: "+sumExpenses);

        log.info("Result: "+(consultantRevenue - sumExpenses));

        return new GraphKeyValue(UUID.randomUUID().toString(), "profits", consultantRevenue - sumExpenses);
    }

    public HashMap<String, Double> getRegisteredRevenueByPeriodAndSingleConsultant(String useruuid, String periodFrom, String periodTo) {
        return workService.getRegisteredRevenueByPeriodAndSingleConsultant(useruuid, periodFrom, periodTo);
    }

    public List<GraphKeyValue> getRegisteredProfitsForSingleConsultant(String useruuid, LocalDate periodStart, LocalDate periodEnd, int interval) {
        int months = (int) ChronoUnit.MONTHS.between(periodStart, periodEnd);
        double revenueSum = 0.0;
        int count = 1;
        List<GraphKeyValue> result = new ArrayList<>();
        List<Finance> companyExpenseList = financeService.findByAccountAndPeriod(ExcelFinanceType.LØNNINGER, periodStart, periodEnd);
        for (int i = 0; i < months; i++) {
            LocalDate currentDate = periodStart.plusMonths(i);

            double consultantCount = userService.findWorkingUsersByDate(currentDate, ConsultantType.CONSULTANT).size();
            double expense = companyExpenseList.stream().filter(e -> e.getPeriod().withDayOfMonth(1).isEqual(currentDate.withDayOfMonth(1))).mapToDouble(Finance::getAmount).sum() / consultantCount;

            if (expense == 0) {
                count = 1;
                revenueSum = 0.0;
                continue;
            }

            double revenue = getRegisteredRevenueForSingleMonthAndSingleConsultant(useruuid, currentDate);
            double userSalary = salaryService.getUserSalaryByMonth(useruuid, currentDate).getSalary();
            double consultantSalaries = userService.calcMonthSalaries(currentDate, ConsultantType.CONSULTANT.toString());
            double partOfTotalSalary = userSalary / consultantSalaries;
            double consultantSalariesSum = financeService.getAllExpensesByMonth(currentDate).stream().mapToDouble(FinanceDocument::getESalaries).sum();
            double grossUserSalary = consultantSalariesSum * partOfTotalSalary;
            double allExpensesSum = financeService.getSumOfExpensesForSingleMonth(currentDate);

            revenueSum += revenue - grossUserSalary - ((allExpensesSum - consultantSalaries) / consultantCount);

            if(count == interval) {
                result.add(new GraphKeyValue(useruuid, stringIt(currentDate), (revenueSum / interval)));
                revenueSum = 0.0;
                count = 1;
                continue;
            }

            count++;
        }
        return result;
    }

    public List<GraphKeyValue> getInvoicedOrRegisteredRevenueByPeriod(LocalDate periodStart, LocalDate periodEnd) {
        Map<LocalDate, Double> invoicedOrRegisteredRevenueMap = new HashMap<>();
        int months = (int) ChronoUnit.MONTHS.between(periodStart, periodEnd);
        for (int i = 0; i < months; i++) {
            LocalDate currentDate = periodStart.plusMonths(i);
            double invoicedAmountByMonth = invoiceService.calculateInvoiceSumByMonth(currentDate);//getInvoicedRevenueForSingleMonth(currentDate);
            if(invoicedAmountByMonth > 0.0) {
                invoicedOrRegisteredRevenueMap.put(currentDate, invoicedAmountByMonth);
            } else {
                invoicedOrRegisteredRevenueMap.put(currentDate, 0.0);
            }
        }

        List<GraphKeyValue> result = new ArrayList<>();
        invoicedOrRegisteredRevenueMap.keySet().stream().sorted(LocalDate::compareTo).forEach(localDate -> {
            result.add(new GraphKeyValue(UUID.randomUUID().toString(), stringIt(localDate), invoicedOrRegisteredRevenueMap.get(localDate)));
        });

        return result;
    }

    public List<GraphKeyValue> getProfitsByPeriod(LocalDate periodStart, LocalDate periodEnd) {
        int months = (int)ChronoUnit.MONTHS.between(periodStart, periodEnd);
        final List<GraphKeyValue> result = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            final LocalDate currentDate = periodStart.plusMonths(i).withDayOfMonth(1);

            final double invoicedAmountByMonth = getInvoicedRevenueForSingleMonth(currentDate);
            final double expense = financeService.getSumOfExpensesForSingleMonth(currentDate);// getAllUserExpensesByMonth(currentDate.withDayOfMonth(1));
            result.add(new GraphKeyValue(UUID.randomUUID().toString(), stringIt(currentDate), invoicedAmountByMonth-expense));

        }
        return result;
    }

    public double getInvoicedRevenueForSingleMonth(LocalDate month) {
        log.info("RevenueService.getInvoicedRevenueForSingleMonth");
        log.info("month = " + month);
        return invoiceService.calculateInvoiceSumByMonth(month);
    }

    public double getRegisteredHoursForSingleMonth(LocalDate month) {
        return workService.getRegisteredHoursForSingleMonth(month);
    }

    public List<GraphKeyValue> findConsultantBillableHoursByPeriod(LocalDate fromDate, LocalDate toDate) {
        List<WorkFull> workList = workService.findByPeriod(fromDate, toDate);
        Map<String, GraphKeyValue> result = new HashMap<>();
        for (WorkFull work : workList) {
            if(work.getWorkduration() == 0) continue;
            result.putIfAbsent(work.getUseruuid(), new GraphKeyValue(work.getUseruuid(), userService.findUserByUuid(work.getUseruuid(), true).getUsername(), 0.0));
            result.get(work.getUseruuid()).addValue(work.getWorkduration());
        }
        return new ArrayList<>(result.values());
    }

    public GraphKeyValue[] getExpectedBonusByPeriod(LocalDate periodStart, LocalDate periodEnd) {
        double forecastedExpenses = 43000;
        double forecastedSalaries = 64000;
        double forecastedConsultants = availabilityService.countActiveEmployeeTypesByMonth(periodEnd, ConsultantType.CONSULTANT, ConsultantType.STAFF);
        double totalForecastedExpenses = (forecastedExpenses + forecastedSalaries) * forecastedConsultants;

        double totalCumulativeRevenue = 0.0;
        GraphKeyValue[] payout = new GraphKeyValue[12];

        for (int i = 0; i < 12; i++) {
            LocalDate currentDate = periodStart.plusMonths(i);
            if(!currentDate.isBefore(periodEnd)) break;

            totalCumulativeRevenue += getRegisteredRevenueForSingleMonth(currentDate);
            double grossMargin = totalCumulativeRevenue - totalForecastedExpenses;
            double grossMarginPerConsultant = grossMargin / forecastedConsultants;
            double consultantPayout = grossMarginPerConsultant * 0.1;
            payout[i] = new GraphKeyValue(UUID.randomUUID().toString(), i+"", NumberUtils.round((consultantPayout / forecastedSalaries) * 100.0 - 100.0, 2));
        }
        return payout;
    }
}
