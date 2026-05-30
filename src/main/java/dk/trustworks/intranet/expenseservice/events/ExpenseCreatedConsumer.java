package dk.trustworks.intranet.expenseservice.events;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerDayAggregate;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
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
        // Phase 1: the un-validated head is exactly state=SUBMITTED with no AI decision yet.
        List<String> expenseUuids = Expense
                .find("state = ?1 AND aiValidationApproved IS NULL",
                        ExpenseStateDeriver.SUBMITTED)
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

        // Idempotency guard: only validate expenses still in the un-decided head (SUBMITTED).
        // Re-validation (employee edit / admin force) resets state to SUBMITTED first.
        if (!ExpenseStateDeriver.SUBMITTED.equals(expense.getState())) {
            log.infof("Skipping validation for uuid=%s — state=%s (not SUBMITTED)",
                    expenseUuid, expense.getState());
            return;
        }

        // Validate expense with OpenAI (30+ seconds, no transaction held)
        ExpenseAIValidationService.AIResult result = validateExpense(expense);

        // Persist validation results in separate transaction (fast writes only)
        persistValidationResult(expense, result);
    }

    @Transactional
    void persistValidationResult(Expense expense, ExpenseAIValidationService.AIResult result) {
        Expense managedExpense = Expense.findById(expense.getUuid());
        if (managedExpense == null) {
            log.warnf("Expense disappeared during validation: %s", expense.getUuid());
            return;
        }

        // Transient API/processing errors: leave AI fields NULL so the sweep retries.
        if (!result.approved() && result.reason() != null &&
                (result.reason().startsWith("AI validation error:") ||
                 result.reason().startsWith("Validation error:"))) {
            log.warnf("Skipping expense %s — transient error: %s (will retry later)",
                    expense.getUuid(), result.reason());
            return;
        }

        // Defensive: a null outcome would NPE the switch below. Treat like a transient
        // error — leave the AI fields null so the SUBMITTED sweep retries it.
        if (result.outcome() == null) {
            log.warnf("Skipping expense %s — null AI outcome (will retry later)", expense.getUuid());
            return;
        }

        // Common AI fields (Phase 1 adds the tier columns).
        managedExpense.setAiValidationApproved(result.approved());
        managedExpense.setAiValidationReason(result.reason());
        managedExpense.setAiValidationCount(managedExpense.getAiValidationCount() + 1);
        managedExpense.setAiOutcome(result.outcome());
        managedExpense.setAiConfidence(result.confidence());
        managedExpense.setSoftFlags(serializeSoftFlags(result.softFlags()));

        switch (result.outcome()) {
            case ExpenseAIValidationService.AIResult.OUTCOME_APPROVE,
                 ExpenseAIValidationService.AIResult.OUTCOME_SOFT_FLAG -> {
                if (classificationService.requiresFinanceReview(managedExpense.getUuid())) {
                    decisionLogs.recordAIApprovalPendingFinanceReview(managedExpense,
                            "AI cleared the receipt, but the selected expense route requires Finance review.");
                    managedExpense.setStatus(ExpenseService.STATUS_CREATED);
                    managedExpense.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
                    managedExpense.setAttentionOwner(ExpenseStateDeriver.OWNER_ACCOUNTING);
                    managedExpense.setAttentionKind(ExpenseStateDeriver.KIND_POLICY);
                    managedExpense.setReviewState("PENDING_HR"); // vestigial dual-write
                    log.infof("Expense %s cleared by AI but routed to ACCOUNTING/POLICY (finance review).",
                            expense.getUuid());
                } else {
                    decisionLogs.recordAIApproval(managedExpense);
                    managedExpense.setStatus(ExpenseService.STATUS_VALIDATED); // pipeline pickup
                    managedExpense.setState(ExpenseStateDeriver.APPROVED);
                    managedExpense.setAttentionOwner(null);
                    managedExpense.setAttentionKind(null);
                    managedExpense.setReviewState(null); // vestigial
                    log.infof("Expense %s APPROVED by AI (outcome=%s). Reason: %s",
                            expense.getUuid(), result.outcome(), result.reason());
                }
            }
            case ExpenseAIValidationService.AIResult.OUTCOME_BLOCK -> {
                List<String> firedRuleIds = result.ruleIds() != null ? result.ruleIds() : List.of();
                String owner, kind, legacyReviewState, primaryRuleId;
                if (result.attentionOwner() != null) {
                    // AI pre-determined routing (e.g. AMOUNT_MISMATCH).
                    owner = result.attentionOwner();
                    kind = result.attentionKind();
                    legacyReviewState = ExpenseStateDeriver.mapAttentionKindToLegacyReviewState(kind);
                    primaryRuleId = firedRuleIds.isEmpty() ? null : firedRuleIds.get(0);
                } else {
                    ExpenseReviewRoutingService.RouteResult route =
                            router.route(firedRuleIds, managedExpense.getAiValidationCount());
                    owner = route.owner();
                    kind = route.kind();
                    legacyReviewState = route.legacyReviewState();
                    primaryRuleId = route.primaryRuleId();
                }
                decisionLogs.recordAIRejection(managedExpense, kind, primaryRuleId, result.reason());
                managedExpense.setStatus(ExpenseService.STATUS_CREATED);
                managedExpense.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
                managedExpense.setAttentionOwner(owner);
                managedExpense.setAttentionKind(kind);
                managedExpense.setReviewState(legacyReviewState); // vestigial dual-write
                managedExpense.setAiRuleId(primaryRuleId);
                managedExpense.setAiRuleIdsJson(serializeRuleIds(firedRuleIds));
                log.infof("Expense %s BLOCKED → NEEDS_ATTENTION %s/%s (rule=%s). Reason: %s",
                        expense.getUuid(), owner, kind, primaryRuleId, result.reason());
            }
            default -> log.warnf("Expense %s: unexpected AI outcome=%s — leaving for retry.",
                    expense.getUuid(), result.outcome());
        }

        managedExpense.persist();
    }

    /** Serialize soft-flag findings to a JSON array literal; null/empty → {@code "[]"}. */
    private String serializeSoftFlags(List<String> softFlags) {
        if (softFlags == null || softFlags.isEmpty()) {
            return "[]";
        }
        return softFlags.stream()
                .map(f -> "\"" + f.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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
            return ExpenseAIValidationService.AIResult.error("Validation error: " + e.getMessage());
        }
    }
}
