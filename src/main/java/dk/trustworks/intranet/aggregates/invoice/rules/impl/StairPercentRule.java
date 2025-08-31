package dk.trustworks.intranet.aggregates.invoice.rules.impl;

import dk.trustworks.intranet.aggregates.invoice.rules.ContractRule;
import dk.trustworks.intranet.aggregates.invoice.rules.RuleContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class StairPercentRule implements ContractRule {
    public static final String CODE = "STAIR_PERCENT";

    public static final class Tier { public double threshold; public double percent; }
    public static final class Params { public List<Tier> tiers; public String applyOn = "SUBTOTAL_BEFORE"; public String label; }

    @Override public String code() { return CODE; }
    @Override public int order() { return 10; }

    @Override public void apply(RuleContext ctx) {
        Params p = ctx.params(CODE, Params.class);
        if (p.tiers == null || p.tiers.isEmpty()) return;

        BigDecimal base = "SUBTOTAL_AFTER".equalsIgnoreCase(p.applyOn) ? ctx.subtotalAfterRules() : ctx.subtotalBeforeRules();
        if (base.signum() <= 0) return;

        p.tiers.sort(Comparator.comparingDouble(t -> t.threshold));
        double pct = 0.0;
        for (Tier t : p.tiers) {
            if (base.compareTo(BigDecimal.valueOf(t.threshold)) >= 0) pct = t.percent; else break;
        }
        if (pct <= 0) return;

        BigDecimal amount = base.multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), ctx.scale(), ctx.rm())
                .negate();

        ctx.removeSystemLinesByRule(CODE);
        ctx.addSystemAmountLine(p.label != null ? p.label : ("Stair discount " + pct + "%"),
                ctx.round(amount), CODE, pct + "% of " + ctx.format(base));
    }
}
