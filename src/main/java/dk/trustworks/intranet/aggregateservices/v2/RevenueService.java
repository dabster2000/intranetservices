package dk.trustworks.intranet.aggregateservices.v2;

import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.FinanceService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.FinanceDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.invoiceservice.services.InvoiceService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
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
    FinanceService financeService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    SalaryService salaryService;

    @Inject
    TeamService teamService;

    public DateValueDTO getRegisteredRevenueForSingleMonth(String companyuuid, LocalDate month) {
        return getRegisteredRevenueByPeriod(companyuuid, month, month.plusMonths(1)).get(0);
    }

    public List<DateValueDTO> getRegisteredRevenueByPeriod(String companyuuid, LocalDate fromdate, LocalDate todate) {
        String sql = "select w.registered as date, sum(ifnull(w.rate, 0) * w.workduration) AS value from work_full w " +
                "where w.rate > 0 and w.consultant_company_uuid = '"+companyuuid+"' and registered >= '" + stringIt(fromdate) + "' and registered < '" + stringIt(todate) + "' " +
                "group by w.consultant_company_uuid, year(w.registered), month(w.registered);";
        log.info("getRegisteredRevenueByPeriod sql: "+sql);
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
    }

    public List<KeyValueDTO> getRegisteredRevenuePerClient(String companyuuid, List<String> clientuuids) {
        List<Object[]> resultList = em.createNativeQuery(
                "select w.clientuuid clientuuid, sum(w.rate * w.workduration) as amount from work_full w " +
                "where w.consultant_company_uuid = '"+companyuuid+"' registered >= '2021-07-01' and registered < '2022-07-01' and clientuuid in ('" + String.join("','", clientuuids) + "') group by w.clientuuid").getResultList();
        List<KeyValueDTO> result = new ArrayList<>();
        for (Object[] objects : resultList) {
            result.add(new KeyValueDTO((String)objects[0], ""+objects[1]));
        }
        return result;
    }

    public List<GraphKeyValue> getSumOfRegisteredRevenueByClient(String companyuuid) {
        Map<String, GraphKeyValue> clientRevenueMap = new HashMap<>();
        List<Client> clients = clientService.listAllClients();
        List<KeyValueDTO> keyValueDTOS = getRegisteredRevenuePerClient(companyuuid, clients.stream().map(Client::getUuid).collect(Collectors.toList()));
        for (KeyValueDTO keyValueDTO : keyValueDTOS) {
            String key = keyValueDTO.getKey();
            clientRevenueMap.putIfAbsent(key, new GraphKeyValue(key, clients.stream().filter(client -> client.getUuid().equalsIgnoreCase(key)).findFirst().orElse(new Client()).getName(), 0));
            clientRevenueMap.get(key).setValue(clientRevenueMap.get(key).getValue()+Double.parseDouble(keyValueDTO.getValue()));
        }
        return List.copyOf(clientRevenueMap.values());
    }

    public List<GraphKeyValue> getSumOfRegisteredRevenueByClientByFiscalYear(String companyuuid, int fiscalYear) {
        LocalDate fiscalYearDate = DateUtils.getCurrentFiscalStartDate().withYear(fiscalYear);
                Map<String, Double> workList = workService.findByPeriod(fiscalYearDate, fiscalYearDate.plusYears(1)).stream()
                .filter(work -> work.getConsultant_company_uuid().equals(companyuuid) && work.getRate()>0)
                .collect(Collectors.groupingBy(WorkFull::getClientuuid, Collectors.summingDouble(work -> work.getWorkduration()*work.getRate())));
        return workList.keySet().stream().map(s -> new GraphKeyValue(s, "", workList.get(s))).collect(Collectors.toList());
    }

    public List<GraphKeyValue> getRegisteredHoursPerConsultantForSingleMonth(String companyuuid, LocalDate month) {
        List<WorkFull> workFullList = WorkFull.find("registered >= ?1 AND registered < ?2 and rate > 0.0 and consultant_company_uuid = ?3", month.withDayOfMonth(1), month.withDayOfMonth(1).plusMonths(1), companyuuid).list();
        return workFullList.stream().map(work -> new GraphKeyValue(work.getUseruuid(), stringIt(month), work.getWorkduration())).collect(Collectors.toList());
    }

    public List<DateValueDTO> getRegisteredHoursPerSingleConsultantByPeriod(String companyuuid, String useruuid, LocalDate fromdate, LocalDate todate) {
        List<WorkFull> workFullList = WorkFull.find("useruuid like ?1 AND registered >= ?2 AND registered < ?3 AND rate > 0.0 and consultant_company_uuid = ?4", useruuid, fromdate, todate, companyuuid).list();
        return workFullList.stream().map(work -> new DateValueDTO(work.getRegistered().withDayOfMonth(1), work.getWorkduration())).toList();
    }

    public double getRegisteredHoursForSingleMonthAndSingleConsultant(String companyuuid, String useruuid, LocalDate month) {
        List<WorkFull> workFullList = WorkFull.find("useruuid like ?1 AND registered >= ?2 AND registered < ?3 AND rate > 0.0 and consultant_company_uuid = ?4", useruuid, month.withDayOfMonth(1), month.withDayOfMonth(1).plusMonths(1), companyuuid).list();
        return workFullList.stream().mapToDouble(WorkFull::getWorkduration).sum();
    }

    public double getRegisteredRevenueForSingleMonthAndSingleConsultant(String companyuuid, String useruuid, LocalDate month) {
        List<WorkFull> workFullList = WorkFull.find("useruuid like ?1 AND registered >= ?2 AND registered < ?3 AND rate > 0.0 and consultant_company_uuid = ?4", useruuid, month.withDayOfMonth(1), month.withDayOfMonth(1).plusMonths(1), companyuuid).list();
        return workFullList.stream().mapToDouble(value -> value.getWorkduration()*value.getRate()).sum();
    }

    public GraphKeyValue getTotalTeamProfits(String companyuuid, LocalDate fiscalYear, List<String> teams) {
        return getTotalTeamProfits(companyuuid, fiscalYear, fiscalYear.plusYears(1).minusMonths(1), teams);
    }

    public GraphKeyValue getTotalTeamProfits(String companyuuid, LocalDate fromdate, LocalDate todate, List<String> teams) {
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
            double singleConsultantRevenue = getRegisteredRevenueByPeriodAndSingleConsultant(companyuuid, user.getUuid(), stringIt(fromdate), stringIt(todate)).values().stream().mapToDouble(value -> value).sum();
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

    public HashMap<String, Double> getRegisteredRevenueByPeriodAndSingleConsultant(String companyuuid, String useruuid, String periodFrom, String periodTo) {
        HashMap<String, Double> resultMap = new HashMap<>();
        try (Stream<WorkFull> workStream = WorkFull.stream("useruuid like ?1 AND registered >= ?2 AND registered < ?3 AND rate > 0.0 and consultant_company_uuid = ?4", useruuid, dateIt(periodFrom).withDayOfMonth(1), dateIt(periodTo).withDayOfMonth(1).plusMonths(1), companyuuid)) {
            workStream.forEach(work -> {
                String date = stringIt(work.getRegistered().withDayOfMonth(1));
                resultMap.putIfAbsent(date, 0.0);
                resultMap.put(date, resultMap.get(date)+(work.getWorkduration()*work.getRate()));
            });
            //.mapToDouble(value -> value.getWorkduration()*value.getRate()).sum();
        }
        return resultMap;
    }

    public List<GraphKeyValue> getRegisteredProfitsForSingleConsultant(String companyuuid, String useruuid, LocalDate periodStart, LocalDate periodEnd, int interval) {
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

            double revenue = getRegisteredRevenueForSingleMonthAndSingleConsultant(companyuuid, useruuid, currentDate);
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

    // TODO: Make this add registered revenue for months that are not invoiced yet
    public List<GraphKeyValue> getInvoicedOrRegisteredRevenueByPeriod(String companyuuid, LocalDate periodStart, LocalDate periodEnd) {
        Map<LocalDate, Double> invoicedOrRegisteredRevenueMap = new HashMap<>();
        int months = (int) ChronoUnit.MONTHS.between(periodStart, periodEnd);
        for (int i = 0; i < months; i++) {
            LocalDate currentDate = periodStart.plusMonths(i);
            double invoicedAmountByMonth = invoiceService.calculateInvoiceSumByMonth(companyuuid, currentDate);//getInvoicedRevenueForSingleMonth(currentDate);
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

    public List<DateValueDTO> getInvoicedRevenueByPeriod(String companyuuid, LocalDate periodStart, LocalDate periodEnd) {
        List<DateValueDTO> result = new ArrayList<>();
        int months = (int) ChronoUnit.MONTHS.between(periodStart, periodEnd);
        for (int i = 0; i < months; i++) {
            LocalDate currentDate = periodStart.plusMonths(i);
            double invoicedAmountByMonth = invoiceService.calculateInvoiceSumByMonthWorkWasDone(companyuuid, currentDate);//getInvoicedRevenueForSingleMonth(currentDate);
            result.add(new DateValueDTO(currentDate, invoicedAmountByMonth));
        }
        return result;
    }

    public List<GraphKeyValue> getProfitsByPeriod(String companyuuid, LocalDate periodStart, LocalDate periodEnd) {
        int months = (int)ChronoUnit.MONTHS.between(periodStart, periodEnd);
        final List<GraphKeyValue> result = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            final LocalDate currentDate = periodStart.plusMonths(i).withDayOfMonth(1);

            final double invoicedAmountByMonth = getInvoicedRevenueForSingleMonth(companyuuid, currentDate);
            final double expense = financeService.getSumOfExpensesForSingleMonth(currentDate);// getAllUserExpensesByMonth(currentDate.withDayOfMonth(1));
            result.add(new GraphKeyValue(UUID.randomUUID().toString(), stringIt(currentDate), invoicedAmountByMonth-expense));

        }
        return result;
    }

    public double getInvoicedRevenueForSingleMonth(String companyuuid, LocalDate month) {
        log.info("RevenueService.getInvoicedRevenueForSingleMonth");
        log.info("month = " + month);
        return invoiceService.calculateInvoiceSumByMonth(companyuuid, month);
    }

    public double getRegisteredHoursForSingleMonth(LocalDate month) {
        try (Stream<WorkFull> workStream = WorkFull.stream("registered >= ?1 AND registered < ?2 and rate > 0.0", month.withDayOfMonth(1), month.withDayOfMonth(1).plusMonths(1))) {
            return workStream.mapToDouble(WorkFull::getWorkduration).sum();
        }
    }

}
