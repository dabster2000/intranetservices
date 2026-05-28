package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDecisionLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@ApplicationScoped
public class ExpenseDecisionLogService {

    @Transactional
    public void recordAIApproval(Expense e) {
        append(e, "AI", null, "AI_VALIDATED_APPROVED",
               e.getStatus(), "VALIDATED", e.getReviewState(), null, null, null);
    }

    @Transactional
    public void recordAIApprovalPendingFinanceReview(Expense e, String reason) {
        append(e, "AI", null, "AI_VALIDATED_APPROVED",
               e.getStatus(), e.getStatus(), e.getReviewState(), "PENDING_HR", null, reason);
    }

    @Transactional
    public void recordAIRejection(Expense e, String toReviewState, String primaryRuleId, String reason) {
        append(e, "AI", null, "AI_VALIDATED_REJECTED",
               e.getStatus(), e.getStatus(), e.getReviewState(), toReviewState, primaryRuleId, reason);
    }

    @Transactional
    public void recordEmployeeEdit(Expense e, String actorUuid) {
        append(e, "EMPLOYEE", actorUuid, "EMPLOYEE_FIX_SUBMITTED",
               e.getStatus(), e.getStatus(), e.getReviewState(), null, null, null);
    }

    @Transactional
    public void recordEmployeeJustification(Expense e, String actorUuid, String justification) {
        append(e, "EMPLOYEE", actorUuid, "EMPLOYEE_JUSTIFICATION_SUBMITTED",
               e.getStatus(), e.getStatus(), e.getReviewState(), "PENDING_HR", null, justification);
    }

    @Transactional
    public void recordHRApprove(Expense e, String actorUuid, String reason) {
        append(e, "HR", actorUuid, "HR_APPROVED",
               e.getStatus(), "VALIDATED", e.getReviewState(), null, e.getAiRuleId(), reason);
    }

    @Transactional
    public void recordHRSendBack(Expense e, String actorUuid, String comment) {
        append(e, "HR", actorUuid, "HR_SENT_BACK",
               e.getStatus(), e.getStatus(), e.getReviewState(), "HR_SENT_BACK", e.getAiRuleId(), comment);
    }

    @Transactional
    public void recordHRReject(Expense e, String actorUuid, String reason) {
        append(e, "HR", actorUuid, "HR_REJECTED",
               e.getStatus(), "DELETED", e.getReviewState(), null, e.getAiRuleId(), reason);
    }

    @Transactional
    public void recordLegacyOverride(Expense e, String actorUuid) {
        append(e, "HR", actorUuid, "LEGACY_OVERRIDE",
               e.getStatus(), "VALIDATED", null, null, e.getAiRuleId(), null);
    }

    @Transactional
    public void recordAdminForceRevalidate(Expense e, String actorUuid) {
        append(e, "ADMIN", actorUuid, "ADMIN_FORCE_REVALIDATE",
               e.getStatus(), e.getStatus(), e.getReviewState(), null, e.getAiRuleId(), null);
    }

    private void append(Expense e, String actorRole, String actorUuid, String action,
                        String fromStatus, String toStatus,
                        String fromReview,  String toReview,
                        String aiRuleId,   String reason) {
        ExpenseDecisionLog row = new ExpenseDecisionLog();
        row.uuid = UUID.randomUUID().toString();
        row.expenseUuid = e.getUuid();
        row.occurredAt = LocalDateTime.now();
        row.actorRole = actorRole;
        row.actorUuid = actorUuid;
        row.action = action;
        row.fromStatus = fromStatus;
        row.toStatus = toStatus;
        row.fromReviewState = fromReview;
        row.toReviewState = toReview;
        row.aiRuleId = aiRuleId;
        row.reasonText = reason;
        row.persist();
    }

    public java.util.List<ExpenseDecisionLog> findByExpense(String expenseUuid) {
        return ExpenseDecisionLog.list("expenseUuid = ?1 order by occurredAt asc", expenseUuid);
    }
}
