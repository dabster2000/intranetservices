package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.services.UserService;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;


@JBossLog
@ApplicationScoped
public class BudgetService {

    @Inject
    BudgetServiceCache budgetServiceCache;

    @Inject
    UserService userService;

    public List<BudgetDocument> calcBudgets(LocalDate lookupMonth) {
        return budgetServiceCache.createBudgetData().stream().filter(budgetDocument -> budgetDocument.getMonth().withDayOfMonth(1).isEqual(lookupMonth.withDayOfMonth(1))).collect(Collectors.toList());
    }

    public double getConsultantBudgetByMonth(User user, LocalDate month) {
        List<BudgetDocument> budgetData = budgetServiceCache.createBudgetData();
        return budgetData.stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(user.getUuid()) && budgetDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(budgetDocument -> budgetDocument.getBudgetHours() * budgetDocument.getRate()).sum();
    }

    public List<BudgetDocument> getConsultantBudgetDataByMonth(String useruuid, LocalDate month) {
        List<BudgetDocument> budgetData = budgetServiceCache.createBudgetData();
        return budgetData.stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .collect(Collectors.toList());
    }

    public List<BudgetDocument> getBudgetDataByPeriod(LocalDate startDate, LocalDate endDate) {
        List<BudgetDocument> budgetData = budgetServiceCache.createBudgetData();
        return budgetData.stream().filter(budgetDocument ->
                !budgetDocument.getMonth().withDayOfMonth(1).isBefore(startDate) &&
                        !budgetDocument.getMonth().withDayOfMonth(1).isAfter(endDate)
        ).collect(Collectors.toList());
    }

    public List<BudgetDocument> getBudgetDataByPeriod(LocalDate month) {
        List<BudgetDocument> budgetData = budgetServiceCache.createBudgetData();
        return budgetData.stream().filter(budgetDocument ->
                budgetDocument.getMonth().withDayOfMonth(1).isEqual(month.withDayOfMonth(1))
        ).collect(Collectors.toList());
    }

    public double getConsultantBudgetHoursByMonth(String useruuid, LocalDate month) {
        List<BudgetDocument> budgetData = budgetServiceCache.createBudgetData();
        return budgetData.stream()
                .filter(budgetDocument -> budgetDocument.getUser().getUuid().equals(useruuid) && budgetDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(BudgetDocument::getBudgetHours).sum();
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
