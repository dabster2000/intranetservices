package dk.trustworks.intranet.expenseservice.services.rules;

public record ValidationRuleDefinition(
        String id,
        String title,
        String description,
        RuleSeverity severity,
        int priority // lower = higher priority within same severity
) {}