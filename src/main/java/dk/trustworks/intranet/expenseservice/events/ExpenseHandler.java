package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.dto.ExpenseFile;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@JBossLog
@ApplicationScoped
public class ExpenseHandler {

    @Inject
    ExpenseService expenseService;

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    TransactionManager tm;

    @Scheduled(every = "30m")
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
                if (userAccounts.size() == 0) {
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
}


