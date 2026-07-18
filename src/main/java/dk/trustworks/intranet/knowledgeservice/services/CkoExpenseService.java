package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.CKOExpense;
import dk.trustworks.intranet.knowledgeservice.model.KnowledgeBudgetSummary;
import dk.trustworks.intranet.knowledgeservice.model.enums.CKOExpenseStatus;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class CkoExpenseService {

    /**
     * Monthly knowledge budget in DKK. Every year grants 12 x this amount (24.000 DKK)
     * to every employee. Single source of truth — frontends must not hardcode this.
     */
    public static final int MONTHLY_BUDGET = 2000;

    public List<CKOExpense> findAll() {
        return CKOExpense.findAll().list();
    }

    /**
     * Per-calendar-year budget summaries for a user, inclusive of both bounds.
     * Spend = BOOKED + COMPLETED expenses by eventdate calendar year; WISHLIST excluded.
     */
    public List<KnowledgeBudgetSummary> calculateBudgets(String useruuid, Integer fromYear, Integer toYear) {
        int currentYear = LocalDate.now().getYear();
        int from = fromYear != null ? fromYear : currentYear;
        int to = toYear != null ? toYear : currentYear;
        if (to < from) {
            int swap = from;
            from = to;
            to = swap;
        }
        List<CKOExpense> expenses = findExpensesByUseruuid(useruuid);
        int yearlyBudget = 12 * MONTHLY_BUDGET;
        List<KnowledgeBudgetSummary> result = new ArrayList<>();
        for (int year = from; year <= to; year++) {
            final int y = year;
            int spent = expenses.stream()
                    .filter(e -> e.getEventdate() != null && e.getEventdate().getYear() == y)
                    .filter(e -> e.getStatus() != CKOExpenseStatus.WISHLIST)
                    .mapToInt(CKOExpense::getPrice)
                    .sum();
            result.add(new KnowledgeBudgetSummary(y, yearlyBudget, spent, yearlyBudget - spent));
        }
        return result;
    }

    public List<CKOExpense> findExpensesByUseruuid(String useruuid) {
        return CKOExpense.find("useruuid like ?1", useruuid).list();
    }

    public List<CKOExpense> findByDescription(String description) {
        return CKOExpense.find("description like ?1", new String(Base64.getDecoder().decode(description))).list();
    }

    @Transactional
    public void saveExpense(CKOExpense ckoExpense) {
        log.info("CkoExpenseService.saveExpense");
        log.info("ckoExpense = " + ckoExpense);
        if(ckoExpense.getUuid()==null || ckoExpense.getUuid().isEmpty()) {
            ckoExpense.setUuid(UUID.randomUUID().toString());
        } else {
            if(CKOExpense.findByIdOptional(ckoExpense.getUuid()).isPresent()) {
                updateExpense(ckoExpense);
                return;
            }
        }
        CKOExpense.persist(ckoExpense);
    }

    @Transactional
    public void updateExpense(CKOExpense ckoExpense) {
        log.info("CkoExpenseService.updateExpense");
        log.info("ckoExpense = " + ckoExpense);
        CKOExpense.update("eventdate = ?1, " +
                "description = ?2, " +
                "price = ?3, " +
                "comment = ?4, " +
                "status = ?5 " +
                "where uuid like ?6 ",
                ckoExpense.getEventdate(),
                ckoExpense.getDescription(),
                ckoExpense.getPrice(),
                ckoExpense.getComment(),
                ckoExpense.getStatus(),
                ckoExpense.getUuid());
    }

    @Transactional
    public void deleteExpense(String uuid) {
        CKOExpense.deleteById(uuid);
    }
}
