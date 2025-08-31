package dk.trustworks.intranet.aggregates.invoice.rules.impl;

import dk.trustworks.intranet.aggregates.invoice.rules.ContractRule;
import dk.trustworks.intranet.aggregates.invoice.rules.RuleContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;

@ApplicationScoped
public class FixedFeeRule implements ContractRule {
    public static final String CODE = "FIXED_FEE";
    public static final class Params { public double amount = 0.0; public String label; public boolean asDiscount = true; }
    @Override public String code() { return CODE; }
    @Override public int order() { return 30; }

    @Override public void apply(RuleContext ctx) {
        Params p = ctx.params(CODE, Params.class);
        if (p.amount <= 0) return;
        BigDecimal amt = BigDecimal.valueOf(p.amount);
        if (p.asDiscount) amt = amt.negate();

        ctx.removeSystemLinesByRule(CODE);
        ctx.addSystemAmountLine(p.label != null ? p.label : (p.asDiscount ? "Discount" : "Fee"),
                ctx.round(amt), CODE, ctx.format(amt.abs()));
    }
}
