package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ExpenseReviewRoutingService {

    /**
     * Phase 1 routing result for an AI BLOCK. {@code state} is always NEEDS_ATTENTION when
     * routing; this carries the owner/kind (the new authoritative head attributes), the legacy
     * {@code review_state} (kept for the vestigial dual-write + audit log), and the primary rule.
     */
    public record RouteResult(String owner, String kind, String legacyReviewState, String primaryRuleId) {}

    @Inject AIConfigSnapshot config;

    public RouteResult route(List<String> firedRuleIds, int aiValidationCount) {
        int cap = config.getIntParameter("max_ai_revalidations", 3);
        boolean atCap = aiValidationCount >= cap;

        var firedRules = firedRuleIds.stream()
            .map(config::getRule)
            .filter(java.util.Objects::nonNull)
            .toList();

        var judgments = firedRules.stream()
            .filter(r -> "JUDGMENT".equals(r.resolutionType()))
            .sorted(Comparator.comparingInt(AIConfigSnapshot.RuleView::priority))
            .toList();
        var autoFixes = firedRules.stream()
            .filter(r -> "AUTO_FIX".equals(r.resolutionType()))
            .sorted(Comparator.comparingInt(AIConfigSnapshot.RuleView::priority))
            .toList();

        if (!judgments.isEmpty()) {
            return justification(judgments.get(0).ruleId());
        }
        if (atCap && !autoFixes.isEmpty()) {
            return justification(autoFixes.get(0).ruleId());
        }
        if (!autoFixes.isEmpty()) {
            String ruleId = autoFixes.get(0).ruleId();
            return new RouteResult(ExpenseStateDeriver.OWNER_EMPLOYEE, ExpenseStateDeriver.KIND_RECEIPT,
                    "NEEDS_FIX", ruleId);
        }
        // No matching rules → defensive default (employee justification).
        return justification(null);
    }

    private RouteResult justification(String ruleId) {
        return new RouteResult(ExpenseStateDeriver.OWNER_EMPLOYEE, ExpenseStateDeriver.KIND_JUSTIFICATION,
                "NEEDS_JUSTIFICATION", ruleId);
    }
}
