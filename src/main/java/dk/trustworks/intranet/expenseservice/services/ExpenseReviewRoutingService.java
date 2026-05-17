package dk.trustworks.intranet.expenseservice.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ExpenseReviewRoutingService {

    public record Decision(String reviewState, String primaryRuleId) {}

    @Inject AIConfigSnapshot config;

    public Decision route(List<String> firedRuleIds, int aiValidationCount) {
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
            return new Decision("NEEDS_JUSTIFICATION", judgments.get(0).ruleId());
        }
        if (atCap && !autoFixes.isEmpty()) {
            return new Decision("NEEDS_JUSTIFICATION", autoFixes.get(0).ruleId());
        }
        if (!autoFixes.isEmpty()) {
            return new Decision("NEEDS_FIX", autoFixes.get(0).ruleId());
        }
        // No matching rules → defensive default
        return new Decision("NEEDS_JUSTIFICATION", null);
    }
}
