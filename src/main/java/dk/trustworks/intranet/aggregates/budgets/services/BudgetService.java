package dk.trustworks.intranet.aggregates.budgets.services;

import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerMonth;
import dk.trustworks.intranet.aggregates.model.v2.CompanyBudgetPerMonth;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;


@JBossLog
@ApplicationScoped
public class BudgetService {

    @PersistenceUnit
    EntityManager em;

    public List<CompanyBudgetPerMonth> getCompanyBudgetsByPeriod(Company company, LocalDate startDate, LocalDate endDate) {
        String sql = "STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') " +
                "AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') " +
                "and company = ?5";
        return getCompanyBudgetPerMonths(company, startDate, endDate, sql);
    }

    @SuppressWarnings("unchecked")
    public List<DateValueDTO> getContractConsultantBudgetAmount(String contractuuid, String useruuid) {
        String sql = "SELECT ad.document_date AS date, " +
                "       SUM(ad.budgetHours * ad.rate) AS value " +
                "FROM fact_budget_day ad " +
                "WHERE ad.budgetHours > 0 " +
                "  AND ad.contractuuid = :contractuuid " +
                "  AND ad.useruuid = :useruuid " +
                "GROUP BY ad.year, ad.month";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("contractuuid", contractuuid)
                .setParameter("useruuid", useruuid)
                .getResultList()).stream()
                .filter(tuple -> tuple.get("date", LocalDate.class) != null)
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<DateValueDTO> getCompanyBudgetAmountByPeriod(String companyuuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date AS date, " +
                "       SUM(ad.budgetHours * ad.rate) AS value " +
                "FROM fact_budget_day ad " +
                "WHERE ad.budgetHours > 0 " +
                "  AND ad.companyuuid = :companyuuid " +
                "  AND ad.document_date >= :fromdate " +
                "  AND ad.document_date < :todate " +
                "GROUP BY ad.companyuuid, ad.year, ad.month";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("companyuuid", companyuuid)
                .setParameter("fromdate", fromdate)
                .setParameter("todate", todate)
                .getResultList()).stream()
                .filter(tuple -> tuple.get("date", LocalDate.class) != null)
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public DateValueDTO getCompanyBudgetAmountForSingleMonth(String companyuuid, LocalDate date) {
        String sql = "SELECT ad.document_date AS date, " +
                "       SUM(ad.budgetHours * ad.rate) AS value " +
                "FROM fact_budget_day ad " +
                "WHERE ad.budgetHours > 0 " +
                "  AND ad.companyuuid = :companyuuid " +
                "  AND ad.year = :year " +
                "  AND ad.month = :month " +
                "GROUP BY ad.companyuuid, ad.year, ad.month";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("companyuuid", companyuuid)
                .setParameter("year", date.getYear())
                .setParameter("month", date.getMonthValue())
                .getResultList()).stream()
                .filter(tuple -> tuple.get("date", LocalDate.class) != null)
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).findAny().orElse(new DateValueDTO(date, 0.0));
    }

    @SuppressWarnings("unchecked")
    public List<DateValueDTO> getBudgetAmountByPeriodAndSingleConsultant(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date AS date, " +
                "       SUM(ad.budgetHours * ad.rate) AS value " +
                "FROM fact_budget_day ad " +
                "WHERE ad.budgetHours > 0 " +
                "  AND ad.useruuid = :useruuid " +
                "  AND ad.document_date >= :fromdate " +
                "  AND ad.document_date < :todate " +
                "GROUP BY ad.year, ad.month";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("fromdate", fromdate)
                .setParameter("todate", todate)
                .getResultList()).stream()
                .filter(tuple -> tuple.get("date", LocalDate.class) != null)
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    public DateValueDTO getBudgetAmountForSingleMonthAndSingleConsultant(String useruuid, LocalDate date) {
        String sql = "SELECT ad.document_date AS date, " +
                "       SUM(ad.budgetHours * ad.rate) AS value " +
                "FROM fact_budget_day ad " +
                "WHERE ad.budgetHours > 0 " +
                "  AND ad.useruuid = :useruuid " +
                "  AND ad.year = :year " +
                "  AND ad.month = :month " +
                "GROUP BY ad.year, ad.month";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("year", date.getYear())
                .setParameter("month", date.getMonthValue())
                .getResultList()).stream()
                .filter(tuple -> tuple.get("date", LocalDate.class) != null)
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).findAny().orElse(new DateValueDTO(date, 0.0));
    }

    @SuppressWarnings("unchecked")
    public List<DateValueDTO> getBudgetHoursByPeriodAndSingleConsultant(String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date AS date, " +
                "       SUM(ad.budgetHours) AS value " +
                "FROM fact_budget_day ad " +
                "WHERE ad.budgetHours > 0 " +
                "  AND ad.useruuid = :useruuid " +
                "  AND ad.document_date >= :fromdate " +
                "  AND ad.document_date < :todate " +
                "GROUP BY ad.year, ad.month";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("useruuid", useruuid)
                .setParameter("fromdate", fromdate)
                .setParameter("todate", todate)
                .getResultList()).stream()
                .filter(tuple -> tuple.get("date", LocalDate.class) != null)
                .map(tuple -> new DateValueDTO(
                        tuple.get("date", LocalDate.class).withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();
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

    @NotNull
    private ArrayList<CompanyBudgetPerMonth> getCompanyBudgetPerMonths(Company company, LocalDate startDate, LocalDate endDate, String sql) {
        return new ArrayList<>(EmployeeBudgetPerDayAggregate.<EmployeeBudgetPerDayAggregate>list(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), company)
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(
                                Arrays.asList(e.getYear(), e.getMonth(), e.getClient(), e.getContract()), // Key now includes year, month, client, and contract
                                e
                        ),
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

                            return new CompanyBudgetPerMonth(year, month, client, contract, budgetHours, expectedRevenue);
                        })
                ))
                .values());
    }

    @NotNull
    private List<EmployeeBudgetPerMonth> getEmployeeBudgetPerMonths(ArrayList<EmployeeBudgetPerDayAggregate> aggregates) {
        return aggregates
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(
                                new AbstractMap.SimpleEntry<>(
                                        new AbstractMap.SimpleEntry<>(
                                                new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()),
                                                e.getUser()
                                        ),
                                        e.getClient()
                                ),
                                e.getContract()
                        ),Collectors.collectingAndThen(Collectors.toList(), list -> {
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
