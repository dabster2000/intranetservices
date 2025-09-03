package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseAIValidationService;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ExpenseCreatedConsumer {

    @Inject
    ExpenseService expenseService;

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    ExpenseAIValidationService aiValidationService;

    @Inject
    SlackService slackService;
    @Inject
    UserService userService;

    @Scheduled(every = "1m")
    public void expenseSyncJob() {
        Expense.find("status", "CREATED").stream().forEach(expense -> ExpenseCreatedConsumer.this.onExpenseCreated(((Expense) expense).getUuid()));
    }

    @Incoming("expenses-created-in")
    @Blocking
    @Transactional
    public void onExpenseCreated(String expenseUuid) {
        try {
            log.infof("Received expense created event for uuid=%s", expenseUuid);
            Expense expense = Expense.findById(expenseUuid);
            if (expense == null) {
                log.warnf("Expense not found for uuid=%s", expenseUuid);
                return;
            }

            if (!ExpenseService.STATUS_CREATED.equals(expense.getStatus())) {
                log.infof("Skipping validation for uuid=%s with status=%s", expenseUuid, expense.getStatus());
                return;
            }

            // 1) Fetch attachment
            ExpenseFile expenseFile = expenseFileService.getFileById(expenseUuid);
            String attachmentContent = expenseFile != null ? expenseFile.getExpensefile() : null;

            // 2) Extract expense date and amount from attachment via OpenAI
            ExpenseAIValidationService.ExtractedExpenseData extracted = aiValidationService.extractExpenseData(attachmentContent);

            // Determine date to use for context
            LocalDate contextDate = extracted.date() != null ? extracted.date() : (expense.getExpensedate() != null ? expense.getExpensedate() : expense.getDatecreated());

            // 3) Gather user and contact info
            User user = User.findById(expense.getUseruuid());
            UserContactinfo contact = UserContactinfo.findByUseruuid(expense.getUseruuid());

            // 4) Get BI data for the relevant day
            BiDataPerDay bi = null;
            if (user != null && contextDate != null) {
                bi = BiDataPerDay.find("user = ?1 and documentDate = ?2", user, contextDate).firstResult();
            }

            // 5) Get Budget aggregates for the relevant day
            List<EmployeeBudgetPerDayAggregate> budgets = null;
            if (user != null && contextDate != null) {
                budgets = EmployeeBudgetPerDayAggregate.find("user = ?1 and documentDate = ?2", user, contextDate).list();
            }

            // 6) Send all data to OpenAI to validate
            ExpenseAIValidationService.ValidationDecision decision = aiValidationService.validateWithContext(
                    expense, extracted, user, contact, bi, budgets
            );

            if (decision.approved()) {
                expenseService.updateStatus(expense, ExpenseService.STATUS_VALIDATED);
                slackService.sendMessage(userService.findByUsername("hans.lassen", true), decision.reason());
                log.infof("Expense %s validated by AI. Reason: %s", expenseUuid, decision.reason());
            } else {
                expenseService.updateStatus(expense, ExpenseService.STATUS_VALIDATED);
                log.infof("Expense %s NOT validated by AI. Reason: %s", expenseUuid, decision.reason());
            }
        } catch (Exception e) {
            log.error("Error processing expense created event for uuid=" + expenseUuid, e);
        }
    }
}
