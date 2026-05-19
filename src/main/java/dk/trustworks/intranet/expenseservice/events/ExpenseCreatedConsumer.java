package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseAIValidationService;
import dk.trustworks.intranet.expenseservice.services.ExpenseClassificationService;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseReviewRoutingService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

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
    ExpenseReviewRoutingService router;

    @Inject
    ExpenseDecisionLogService decisionLogs;

    @Inject
    ExpenseClassificationService classificationService;

    @Scheduled(every = "50m")
    public void expenseSyncJob() {
        // Eager loading to avoid ResultSet timeout during long-running OpenAI processing
        // Only process expenses that haven't been validated yet (aiValidationApproved IS NULL)
        // AND that have not already been routed into a review state.
        List<String> expenseUuids = Expense
                .find("status = ?1 AND aiValidationApproved IS NULL AND reviewState IS NULL",
                        ExpenseService.STATUS_CREATED)
                .stream()
                .map(e -> ((Expense) e).getUuid())
                .toList();

        // Process each expense (30+ seconds per expense for OpenAI validation)
        expenseUuids.forEach(this::onExpenseCreated);
    }

    @ConsumeEvent(value = "expense.validate", blocking = true)
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

        // Idempotency guard: if the expense is already routed into a review state,
        // do not re-validate. Manual re-validation goes through the edit endpoint.
        if (expense.getReviewState() != null) {
            log.infof("Skipping validation for uuid=%s — already in reviewState=%s",
                    expenseUuid, expense.getReviewState());
            return;
        }

        // Validate expense with OpenAI (30+ seconds, no transaction held)
        ExpenseAIValidationService.AIResult result = validateExpense(expense);

        // Persist validation results in separate transaction (fast writes only)
        persistValidationResult(expense, result);
    }

    @Transactional
    void persistValidationResult(Expense expense, ExpenseAIValidationService.AIResult result) {
        // Reload expense to ensure fresh entity in transaction context
        Expense managedExpense = Expense.findById(expense.getUuid());
        if (managedExpense == null) {
            log.warnf("Expense disappeared during validation: %s", expense.getUuid());
            return;
        }

        // Skip database update for API/processing errors — leave aiValidationApproved NULL so the
        // scheduled sweep retries. Detect both "AI validation error:" and "Validation error:" prefixes.
        if (!result.approved() && result.reason() != null &&
                (result.reason().startsWith("AI validation error:") ||
                 result.reason().startsWith("Validation error:"))) {
            log.warnf("Skipping expense %s — transient error: %s (will retry later)",
                    expense.getUuid(), result.reason());
            return;  // Early exit — aiValidationApproved stays NULL for retry
        }

        // Store AI validation result in database (only for legitimate decisions)
        managedExpense.setAiValidationApproved(result.approved());
        managedExpense.setAiValidationReason(result.reason());
        managedExpense.setAiValidationCount(managedExpense.getAiValidationCount() + 1);

        if (result.approved()) {
            if (classificationService.requiresFinanceReview(managedExpense.getUuid())) {
                // Log the transition FIRST so the pre-mutation state is captured.
                decisionLogs.recordAIApprovalPendingFinanceReview(
                        managedExpense,
                        "AI approved the receipt, but the selected expense route requires Finance review.");
                managedExpense.setStatus(ExpenseService.STATUS_CREATED);
                managedExpense.setReviewState("PENDING_HR");
                log.infof("Expense %s APPROVED by AI but routed to PENDING_HR due to classification fallback.",
                        expense.getUuid());
            } else {
                // Log the transition FIRST so recordAIApproval sees the pre-mutation state
                decisionLogs.recordAIApproval(managedExpense);
                // Approved → move to VALIDATED, clear any prior review state.
                managedExpense.setStatus(ExpenseService.STATUS_VALIDATED);
                managedExpense.setReviewState(null);
            }
            log.infof("Expense %s APPROVED by AI. Reason: %s", expense.getUuid(), result.reason());
        } else {
            // Rejected → stays in CREATED status; routed into a review state instead.
            List<String> firedRuleIds = result.ruleIds() != null ? result.ruleIds() : List.of();
            ExpenseReviewRoutingService.Decision decision =
                    router.route(firedRuleIds, managedExpense.getAiValidationCount());
            // Log the transition FIRST so recordAIRejection sees the pre-mutation state
            decisionLogs.recordAIRejection(managedExpense,
                    decision.reviewState(), decision.primaryRuleId(), result.reason());
            managedExpense.setStatus(ExpenseService.STATUS_CREATED);
            managedExpense.setReviewState(decision.reviewState());
            managedExpense.setAiRuleId(decision.primaryRuleId());
            managedExpense.setAiRuleIdsJson(serializeRuleIds(firedRuleIds));
            log.infof("Expense %s ROUTED to review state %s (rule=%s). Reason: %s",
                    expense.getUuid(), decision.reviewState(), decision.primaryRuleId(), result.reason());
        }

        // Persist all changes atomically (validation fields + status + review state)
        managedExpense.persist();
    }

    /**
     * Serialize a list of rule IDs to a JSON array literal. The column
     * {@code expenses.ai_rule_ids_json} is typed {@code json}; null/empty lists
     * become {@code "[]"} so the column always contains valid JSON.
     */
    private String serializeRuleIds(List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return "[]";
        }
        return ruleIds.stream()
                .map(r -> "\"" + r.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    public ExpenseAIValidationService.AIResult validateExpense(Expense expense) {
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
                    extractedText,
                    expense,
                    user,
                    contact,
                    bi,
                    budgets
            );

        } catch (Exception e) {
            log.error("Error processing expense created event for uuid=" + expense.getUuid(), e);
            // Return a rejection decision on error instead of null (prevents NPE)
            return new ExpenseAIValidationService.AIResult(
                    false,
                    "Validation error: " + e.getMessage(),
                    List.of()
            );
        }
    }
}
