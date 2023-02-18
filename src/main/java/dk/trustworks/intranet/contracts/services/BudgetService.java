package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.Budget;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class BudgetService {

    public List<Budget> getBudgets(int year) {
        return Budget.find("year = ?1", year).list();
    }

    public List<Budget> getBudgets(LocalDate lookupDate) {
        return Budget.find("year = ?1 and month = ?2", lookupDate.getYear(), lookupDate.getMonthValue()-1).list();
    }

    @Transactional
    public void replaceMonthBudgets(int year, int month, List<Budget> budgetList) {
        Budget.delete("year = ?1, month = ?2", year, month);
        Budget.persist(budgetList);
    }

    public List<Budget> findAll() {
        return Budget.findAll().list();
    }

    public List<Budget> findByConsultantAndProject(String projectuuid, String consultantuuid) {
        if(projectuuid == null) return Budget.find("consultantuuid like ?1", consultantuuid).list();
        return Budget.find("projectuuid like ?1 and consultantuuid like ?2", projectuuid, consultantuuid).list();
    }

    @Transactional
    public void saveBudget(Budget budget) {
        if(budget.getUuid() == null || budget.getUuid().equalsIgnoreCase("")) budget.setUuid(UUID.randomUUID().toString());
        Budget.delete("year = ?1 AND month = ?2 AND projectuuid LIKE ?3 AND consultantuuid LIKE ?4",
                budget.getYear(),
                budget.getMonth(),
                budget.getProjectuuid(),
                budget.getConsultantuuid());
        Budget.persist(budget);
    }
}
