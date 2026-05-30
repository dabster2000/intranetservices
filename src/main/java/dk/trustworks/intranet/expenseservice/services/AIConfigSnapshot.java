package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.AIPromptTemplate;
import dk.trustworks.intranet.expenseservice.model.AIRuleCatalog;
import dk.trustworks.intranet.expenseservice.model.AIValidationParameter;
import io.quarkus.runtime.Startup;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Startup
@ApplicationScoped
public class AIConfigSnapshot {

    public record RuleView(String ruleId, String displayName, String description,
                           String severity, String resolutionType,
                           int priority, boolean active,
                           String outcomeMode, Double confidenceThreshold) {}

    private static final class State {
        final List<RuleView> rulesByPriority;
        final Map<String, RuleView> rulesById;
        final Map<String, String>   parameters;
        final Map<String, String>   prompts;
        State(List<RuleView> rules, Map<String,String> params, Map<String,String> prompts) {
            this.rulesByPriority = List.copyOf(rules);
            this.rulesById = Map.copyOf(
                rules.stream().collect(java.util.stream.Collectors.toMap(RuleView::ruleId, r -> r)));
            this.parameters = Map.copyOf(params);
            this.prompts    = Map.copyOf(prompts);
        }
    }

    private final AtomicReference<State> state = new AtomicReference<>();

    @PostConstruct
    void init() { reload(); }

    @Transactional
    public synchronized void reload() {
        List<RuleView> rules = AIRuleCatalog.<AIRuleCatalog>listAll()
            .stream()
            .filter(r -> r.active)
            .sorted(Comparator.<AIRuleCatalog>comparingInt(r -> r.priority)
                              .thenComparing(r -> r.ruleId))
            .map(r -> new RuleView(r.ruleId, r.displayName, r.description,
                                   r.severity, r.resolutionType, r.priority, r.active,
                                   r.outcomeMode != null ? r.outcomeMode : "BLOCK",
                                   r.confidenceThreshold != null ? r.confidenceThreshold : 0.0))
            .toList();

        Map<String,String> params = new HashMap<>();
        for (var p : AIValidationParameter.<AIValidationParameter>listAll()) {
            params.put(p.parameterKey, p.parameterValue);
        }
        Map<String,String> prompts = new HashMap<>();
        for (var t : AIPromptTemplate.<AIPromptTemplate>listAll()) {
            prompts.put(t.templateKey, t.body);
        }
        state.set(new State(rules, params, prompts));
    }

    public List<RuleView> getRulesByPriority() { return state.get().rulesByPriority; }
    public RuleView       getRule(String ruleId) { return state.get().rulesById.get(ruleId); }
    public Map<String, String> getParameters() { return state.get().parameters; }
    public String getResolutionType(String ruleId) {
        var r = getRule(ruleId);
        return r == null ? "NONE" : r.resolutionType();
    }
    public String getPromptBody(String templateKey) { return state.get().prompts.get(templateKey); }

    public String getParameter(String key, String fallback) {
        return state.get().parameters.getOrDefault(key, fallback);
    }
    public int getIntParameter(String key, int fallback) {
        try { return Integer.parseInt(getParameter(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }
    public java.math.BigDecimal getDecimalParameter(String key, java.math.BigDecimal fallback) {
        try { return new java.math.BigDecimal(getParameter(key, fallback.toPlainString())); }
        catch (NumberFormatException e) { return fallback; }
    }

    @ConsumeEvent(value = "ai-config.refresh", blocking = true)
    public void onRefreshEvent(String ignored) { reload(); }
}
