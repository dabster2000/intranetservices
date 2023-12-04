package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.model.v2.EmployeeBudgetPerDay;
import dk.trustworks.intranet.aggregateservices.v2.BudgetCalculatingExecutor;
import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.userservice.model.User;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceUnit;
import javax.persistence.Tuple;
import javax.transaction.Transactional;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;


@JBossLog
@ApplicationScoped
public class BudgetService {

    @Inject
    BudgetCalculatingExecutor budgetCalculatingExecutor;

    @Inject
    UserService userService;

    @PersistenceUnit
    EntityManager em;

    public List<DateValueDTO> getBudgetRevenueByPeriod(String companyuuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM twservices.budget_document ad  " +
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

    public DateValueDTO getBudgetRevenueForSingleMonth(String companyuuid, LocalDate date) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM twservices.budget_document ad  " +
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

    public List<DateValueDTO> getBudgetRevenueByPeriodAndSingleConsultant(String companyuuid, String useruuid, LocalDate fromdate, LocalDate todate) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM twservices.budget_document ad  " +
                "WHERE ad.budgetHours > 0  " +
                "  AND ad.companyuuid = '"+companyuuid+"'  " +
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

    public DateValueDTO getBudgetRevenueForSingleMonthAndSingleConsultant(String companyuuid, String useruuid, LocalDate date) {
        String sql = "SELECT ad.document_date                           AS date,  " +
                "       SUM(ad.budgetHours * ad.rate)     AS value  " +
                "FROM twservices.budget_document ad  " +
                "WHERE ad.budgetHours > 0  " +
                "  AND ad.companyuuid = '"+companyuuid+"'  " +
                "  AND ad.useruuid = '"+useruuid+"'  " +
                "  AND ad.year = " + date.getYear() + " " +
                "  AND ad.month = " + date.getMonthValue() + " " +
                "GROUP BY ad.companyuuid, ad.year, ad.month;";
        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList()).stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                )).findAny().orElse(new DateValueDTO(date, 0.0));
    }

    public List<EmployeeBudgetPerDay> calcBudgets(LocalDate lookupMonth) {
        return budgetCalculatingExecutor.findAllBudgetData().stream().filter(budgetDocument -> budgetDocument.getDocumentDate().withDayOfMonth(1).isEqual(lookupMonth.withDayOfMonth(1))).collect(Collectors.toList());
    }

    public double getConsultantBudgetByMonth(User user, LocalDate month) {
        List<EmployeeBudgetPerDay> budgetData = budgetCalculatingExecutor.findAllBudgetData();
        return budgetData.stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(user.getUuid()) && budgetDocument.getDocumentDate().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(budgetDocument -> budgetDocument.getBudgetHours() * budgetDocument.getRate()).sum();
    }

    public List<EmployeeBudgetPerDay> getConsultantBudgetDataByMonth(String useruuid, LocalDate month) {
        List<EmployeeBudgetPerDay> budgetData = budgetCalculatingExecutor.findAllBudgetData();
        return budgetData.stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(month.withDayOfMonth(1)))
                .collect(Collectors.toList());
    }

    public List<EmployeeBudgetPerDay> getBudgetDataByPeriod(LocalDate startDate, LocalDate endDate) {
        List<EmployeeBudgetPerDay> budgetData = budgetCalculatingExecutor.findAllBudgetData();
        return budgetData.stream().filter(budgetDocument ->
                !budgetDocument.getDocumentDate().withDayOfMonth(1).isBefore(startDate) &&
                        !budgetDocument.getDocumentDate().withDayOfMonth(1).isAfter(endDate)
        ).collect(Collectors.toList());
    }

    public List<EmployeeBudgetPerDay> getBudgetDataByUserAndPeriod(String useruuid, LocalDate startDate, LocalDate endDate) {
        return budgetCalculatingExecutor.findAllBudgetDataByUserAndPeriod(useruuid, startDate, endDate);
    }

    public List<EmployeeBudgetPerDay> getBudgetDataByPeriod(LocalDate month) {
        List<EmployeeBudgetPerDay> budgetData = budgetCalculatingExecutor.findAllBudgetData();
        return budgetData.stream().filter(budgetDocument ->
                budgetDocument.getDocumentDate().withDayOfMonth(1).isEqual(month.withDayOfMonth(1))
        ).collect(Collectors.toList());
    }

    public double getConsultantBudgetHoursByMonth(String useruuid, LocalDate month) {
        List<EmployeeBudgetPerDay> budgetData = budgetCalculatingExecutor.findAllBudgetData();
        return budgetData.stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getDocumentDate().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(EmployeeBudgetPerDay::getBudgetHours).sum();
    }

    public double getMonthBudget(LocalDate month) {
        double result = 0.0;
        for (User user : userService.listAll(true)) {
            result += getConsultantBudgetByMonth(user, month);
        }
        return result;
    }

    public List<Budget> findByConsultantAndProject(String projectuuid, String consultantuuid) {
        return Budget.find("projectuuid like ?1 and consultantuuid like ?2", projectuuid, consultantuuid).list();
    }

    public List<Budget> findByMonthAndYear(LocalDate month) {
        return Budget.find("year = ?1 and month = ?2", month.getYear(), month.getMonth().getValue()-1).list();
    }

    public List<Budget> findByYear(LocalDate year) {
        return em.createNativeQuery("select * from (select STR_TO_DATE(CONCAT(year, '-', LPAD(month+1, 2, '00'), '-01'), '%Y-%m-%d') AS date, b.* " +
                "from budgets b ) bu where date > '"+year+"' and date < '"+year.plusYears(1)+"' and budget > 0", Budget.class).getResultList();
    }

    @Transactional
    public void saveBudget(Budget budget) {
        log.info("Saving budget: "+budget);
        Budget.delete("projectuuid like ?1 and consultantuuid like ?2 and year = ?3 and month = ?4",
                budget.getProjectuuid(),
                budget.getConsultantuuid(),
                budget.getYear(),
                budget.getMonth());
        Budget.persist(budget);
    }
}
