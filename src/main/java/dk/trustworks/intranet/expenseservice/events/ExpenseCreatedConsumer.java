package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseAIValidationService;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
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

    @Scheduled(every = "50m")
    public void expenseSyncJob() {
        // Eager loading to avoid ResultSet timeout during long-running OpenAI processing
        // Only process expenses that haven't been validated yet (aiValidationApproved = null)
        List<String> expenseUuids = Expense.find("status = ?1 AND aiValidationApproved IS NULL", ExpenseService.STATUS_CREATED)
                .stream()
                .map(e -> ((Expense) e).getUuid())
                .toList();

        // Process each expense (30+ seconds per expense for OpenAI validation)
        expenseUuids.forEach(this::onExpenseCreated);
    }

    @Incoming("expenses-created-in")
    @Blocking
    public void onExpenseCreated(String expenseUuid) {

        log.infof("Received expense created event for uuid=%s", expenseUuid);
        Expense expense = Expense.findById(expenseUuid);
        if (expense == null) {
            log.warnf("Expense not found for uuid=%s", expenseUuid);
            return;
        }

        if (!ExpenseService.STATUS_CREATED.equals(expense.getStatus())) {
            log.infof("Skipping validation for uuid=%s with status=%s", expenseUuid, expense.getStatus());
            return;  // Actually skip validation when status is not CREATED
        }

        // Validate expense with OpenAI (30+ seconds, no transaction held)
        ExpenseAIValidationService.ValidationDecision decision = validateExpense(expense);

        // Persist validation results in separate transaction (fast writes only)
        persistValidationResult(expense, decision);
    }

    @Transactional
    void persistValidationResult(Expense expense, ExpenseAIValidationService.ValidationDecision decision) {
        // Reload expense to ensure fresh entity in transaction context
        Expense managedExpense = Expense.findById(expense.getUuid());
        if (managedExpense == null) {
            log.warnf("Expense disappeared during validation: %s", expense.getUuid());
            return;
        }

        // Skip database update for API/processing errors - allow retry by scheduled job
        // Detect both "AI validation error:" and "Validation error:" prefixes
        if (!decision.approved() &&
            (decision.reason().startsWith("AI validation error:") ||
             decision.reason().startsWith("Validation error:"))) {
            log.warnf("Skipping expense %s - Temporary error: %s (will retry later)",
                      expense.getUuid(), decision.reason());
            return;  // Early exit - aiValidationApproved stays NULL for retry
        }

        // Store AI validation result in database (only for legitimate decisions)
        managedExpense.setAiValidationApproved(decision.approved());
        managedExpense.setAiValidationReason(decision.reason());
        managedExpense.persist();

        if (decision.approved()) {
            expenseService.updateStatus(managedExpense, ExpenseService.STATUS_VALIDATED);
            log.infof("Expense %s APPROVED by AI. Reason: %s", expense.getUuid(), decision.reason());
        } else {
            expenseService.updateStatus(managedExpense, ExpenseService.STATUS_CREATED);
            log.infof("Expense %s REJECTED by AI. Reason: %s", expense.getUuid(), decision.reason());
        }
    }

    public ExpenseAIValidationService.ValidationDecision validateExpense(Expense expense) {
        try {
            // 1) Fetch attachment (receipt image)
            ExpenseFile expenseFile = expenseFileService.getFileById(expense.getUuid());
            String attachmentContent = expenseFile != null ? expenseFile.getExpensefile() : null;

            // 2) Extract comprehensive unstructured text description from receipt image
            String extractedText = aiValidationService.extractExpenseData(attachmentContent);

            // Determine date to use for context (fallback to expense record dates)
            LocalDate contextDate = expense.getExpensedate() != null ? expense.getExpensedate() : expense.getDatecreated();

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

            // 6) Validate expense using extracted text description (not image)

            return aiValidationService.validateWithExtractedText(
                    extractedText,  // NEW: pass extracted text instead of image
                    expense,
                    user,
                    contact,
                    bi,
                    budgets
            );  // Return the actual decision object

        } catch (Exception e) {
            log.error("Error processing expense created event for uuid=" + expense.getUuid(), e);
            // Return a rejection decision on error instead of null (prevents NPE)
            return new ExpenseAIValidationService.ValidationDecision(
                false,
                "Validation error: " + e.getMessage()
            );
        }
    }
}
