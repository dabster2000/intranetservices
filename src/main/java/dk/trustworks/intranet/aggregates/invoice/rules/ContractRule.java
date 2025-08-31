package dk.trustworks.intranet.aggregates.invoice.rules;

public interface ContractRule {
    String code();
    int order();                 // default priority if binding omits one
    default boolean appliesTo(RuleContext ctx) { return true; }
    void apply(RuleContext ctx);
}
