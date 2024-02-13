package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class ExpenseHandler {

    @Inject
    ExpenseService expenseService;

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    TransactionManager tm;

    //@Scheduled(every = "30m")
    /*
    public void consumeCreate() {
        List<Expense> expenses = Expense.find("status", "oprettet").list();
        AtomicInteger expensesProcessed = new AtomicInteger();
        int expenseCount = expenses.size();
        log.info("Expenses found with status oprettet: " + expenseCount);

        for (Expense expense : expenses) {//get expensefile from AWS
            try {
                tm.begin();
                ExpenseFile expenseFile = expenseFileService.getFileById(expense.getUuid());
                List<UserAccount> userAccounts = UserAccount.find("useruuid = ?1", expense.getUseruuid()).list();
                if (userAccounts.isEmpty()) {
                    log.warn("No user accounts found for expense "+expense);
                    tm.commit();
                    continue;
                }
                UserAccount userAccount = userAccounts.get(0);
                expenseService.sendExpense(expense, expenseFile, userAccount);
                expensesProcessed.getAndIncrement();
                tm.commit();
            } catch (Exception e) {
                log.error("Failed to process expense", e);
                try {
                    tm.setRollbackOnly();
                } catch (SystemException ex) {
                    log.error(ex);
                }
            }
        }

        log.info("Expenses processed: " + expensesProcessed.get());
    }

     */
}


