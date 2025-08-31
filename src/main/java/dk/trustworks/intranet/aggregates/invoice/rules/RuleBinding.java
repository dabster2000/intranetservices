package dk.trustworks.intranet.aggregates.invoice.rules;

import java.util.Map;

public record RuleBinding(String code, int order, Map<String, Object> params) {
    public static RuleBinding of(String code, int order, Map<String, Object> params) {
        return new RuleBinding(code, order, params);
    }
}
