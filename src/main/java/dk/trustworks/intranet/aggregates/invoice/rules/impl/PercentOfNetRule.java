package dk.trustworks.intranet.aggregates.invoice.rules.impl;

import dk.trustworks.intranet.aggregates.invoice.rules.ContractRule;
import dk.trustworks.intranet.aggregates.invoice.rules.RuleContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;

@ApplicationScoped
public class PercentOfNetRule implements ContractRule {
    public static final String CODE = "PERCENT_OF_NET";
    public static final class Params { public double percent = 0.0; public String applyOn = "SUBTOTAL_BEFORE"; public String label; }
    @Override public String code() { return CODE; }
    @Override public int order() { return 20; }

    @Override public void apply(RuleContext ctx) {
        Params p = ctx.params(CODE, Params.class);
        BigDecimal base = "SUBTOTAL_AFTER".equalsIgnoreCase(p.applyOn) ? ctx.subtotalAfterRules() : ctx.subtotalBeforeRules();
        if (base.signum() <= 0 || p.percent <= 0) return;

        BigDecimal amount = base.multiply(BigDecimal.valueOf(p.percent))
                .divide(BigDecimal.valueOf(100), ctx.scale(), ctx.rm())
                .negate();

        ctx.removeSystemLinesByRule(CODE);
        ctx.addSystemAmountLine(p.label != null ? p.label : (p.percent + "% discount"),
                ctx.round(amount), CODE, p.percent + "% of " + ctx.format(base));
    }
}
