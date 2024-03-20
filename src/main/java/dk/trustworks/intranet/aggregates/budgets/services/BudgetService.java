package dk.trustworks.intranet.aggregates.budgets.services;

import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerMonth;
import dk.trustworks.intranet.aggregates.model.v2.CompanyBudgetPerMonth;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;

import java.sql.Date;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;


@JBossLog
@ApplicationScoped
public class BudgetService {

    @PersistenceUnit
    EntityManager em;

    public List<CompanyBudgetPerMonth> getCompanyBudgetsByPeriod(Company company, LocalDate startDate, LocalDate endDate) {
        String sql = "STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5";
        return getCompanyBudgetPerMonths(company, startDate, endDate, sql);
    }

    public List<DateValueDTO> getCompanyBudgetAmountByPeriod(String companyuuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date AS date,  " +
                "       SUM(ad.budgetHours * ad.rate) AS value  " +
                "FROM bi_budget_per_day ad  " +
                "WHERE ad.budgetHours > 0  " +
                "  AND ad.companyuuid = '"+companyuuid+"'  " +
                "  AND ad.document_date >= '" + stringIt(fromdate) + "' " +
                "  AND ad.document_date < '" + stringIt(todate) + "' " +
                "  GROUP BY ad.companyuuid, ad.year, ad.month;";

        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
    }

    public DateValueDTO getCompanyBudgetAmountForSingleMonth(String companyuuid, LocalDate date) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM bi_budget_per_day ad  " +
                "WHERE ad.budgetHours > 0  " +
                "  AND ad.companyuuid = '"+companyuuid+"'  " +
                "  AND ad.year = " + date.getYear() + " " +
                "  AND ad.month = " + date.getMonthValue() + " " +
                "GROUP BY ad.companyuuid, ad.year, ad.month;";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).findAny().orElse(new DateValueDTO(date, 0.0));
    }

    public List<DateValueDTO> getBudgetAmountByPeriodAndSingleConsultant(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM bi_budget_per_day ad  " +
                "WHERE ad.budgetHours > 0  " +
                "  AND ad.useruuid = '"+useruuid+"'  " +
                "  AND ad.document_date >= '" + stringIt(fromdate) + "' " +
                "  AND ad.document_date < '" + stringIt(todate) + "' " +
                "  GROUP BY ad.companyuuid, ad.year, ad.month;";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
    }

    public DateValueDTO getBudgetAmountForSingleMonthAndSingleConsultant(String useruuid, LocalDate date) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM bi_budget_per_day ad  " +
                "WHERE ad.budgetHours > 0  " +
                "  AND ad.useruuid = '"+useruuid+"'  " +
                "  AND ad.year = " + date.getYear() + " " +
                "  AND ad.month = " + date.getMonthValue() + " " +
                "GROUP BY ad.year, ad.month;";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).findAny().orElse(new DateValueDTO(date, 0.0));
    }

    public List<EmployeeBudgetPerMonth> getConsultantBudgetDataByMonth(String useruuid, LocalDate month) {
        return findAllBudgetDataByUserAndPeriod(useruuid, month, month.plusMonths(1));
    }

    public List<EmployeeBudgetPerDayAggregate> findAllBudgetData() {
        return EmployeeBudgetPerDayAggregate.<EmployeeBudgetPerDayAggregate>listAll().stream().map(bdm -> new EmployeeBudgetPerDayAggregate(LocalDate.of(bdm.getYear(), bdm.getMonth(), 1), bdm.getClient(), bdm.getUser(), bdm.getContract(), bdm.getBudgetHours(), bdm.getBudgetHours(), bdm.getRate())).toList();
    }

    public List<EmployeeBudgetPerMonth> findAllBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        String sql = "STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and user = ?5";
        return getEmployeeBudgetPerMonths(new ArrayList<>(EmployeeBudgetPerDayAggregate.list(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), User.findById(useruuid))));
    }

    public List<EmployeeBudgetPerMonth> getBudgetDataByPeriod(LocalDate startDate, LocalDate endDate) {
        String sql = "STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d')";
        return getEmployeeBudgetPerMonths(new ArrayList<>(EmployeeBudgetPerDayAggregate.list(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue())));
    }

    public List<EmployeeBudgetPerMonth> getBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return findAllBudgetDataByUserAndPeriod(useruuid, startDate, endDate);
    }

    public double getConsultantBudgetHoursByMonth(String useruuid, LocalDate month) {
        String sql = "year = ?1 and month = ?2 and user = ?3";
        return EmployeeBudgetPerDayAggregate.<EmployeeBudgetPerDayAggregate>list(sql, month.getYear(), month.getMonthValue(), User.findById(useruuid)).stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();
    }

    public double getSumOfAvailableHoursByUsersAndMonth(LocalDate localDate, String... uuids) {
        return ((Number) em.createNativeQuery("select sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours) as value " +
                "from bi_budget_per_day e " +
                "where e.status_type = 'ACTIVE' and e.consultant_type = 'CONSULTANT' and e.useruuid in ('" + String.join("','", uuids) + "') " +
                "     AND e.year = " + localDate.getYear() + " " +
                "     AND e.month = " + localDate.getMonthValue() + "; ").getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();
    }


    @NotNull
    private ArrayList<CompanyBudgetPerMonth> getCompanyBudgetPerMonths(Company company, LocalDate startDate, LocalDate endDate, String sql) {
        return new ArrayList<>(EmployeeBudgetPerDayAggregate.<EmployeeBudgetPerDayAggregate>list(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), company)
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            EmployeeBudgetPerDayAggregate example = list.get(0);
                            int year = example.getYear();
                            int month = example.getMonth();
                            Client client = example.getClient();
                            Contract contract = example.getContract();

                            // Calculating sum of budgetHours and expectedRevenue
                            double budgetHours = list.stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();
                            double expectedRevenue = list.stream().mapToDouble(e -> e.getBudgetHours() * e.getRate()).sum();

                            return new CompanyBudgetPerMonth(year, month, client, company, contract, budgetHours, expectedRevenue);
                        })
                ))
                .values());
    }

    @NotNull
    private List<EmployeeBudgetPerMonth> getEmployeeBudgetPerMonths(ArrayList<EmployeeBudgetPerDayAggregate> aggregates) {
        return aggregates
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()), e.getUser()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            EmployeeBudgetPerDayAggregate example = list.get(0);
                            int year = example.getYear();
                            int month = example.getMonth();
                            User user = example.getUser();
                            Company company = example.getCompany();
                            Client client = example.getClient();
                            Contract contract = example.getContract();
                            double rate = example.getRate();

                            // Calculating sum of budgetHours and expectedRevenue
                            double budgetHours = list.stream().mapToDouble(EmployeeBudgetPerDayAggregate::getBudgetHours).sum();

                            return new EmployeeBudgetPerMonth(year, month, client, user, company, contract, budgetHours, rate);
                        })
                ))
                .values().stream().toList();
    }
}
