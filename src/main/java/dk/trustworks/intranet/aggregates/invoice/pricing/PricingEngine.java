// src/main/java/dk/trustworks/intranet/aggregates/invoice/pricing/PricingEngine.java
package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.annotation.Timed;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class PricingEngine {

    private static final int SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    @Inject PricingRuleCatalog catalog;
    @Inject MeterRegistry registry;

    @Timed(value = "invoice.pricing.duration", description = "Pricing Engine timing") // Micrometer
    public PriceResult price(Invoice draft, Map<String, String> contractTypeItems) {
        Objects.requireNonNull(draft, "invoice draft");
        final LocalDate date = draft.getInvoicedate() != null ? draft.getInvoicedate() : LocalDate.now();

        // Extract contractType from contractTypeItems map if available, otherwise use default
        String contractType = contractTypeItems != null && contractTypeItems.containsKey("contractType")
            ? contractTypeItems.get("contractType")
            : "FIXED_PRICE";
        var ruleSet = catalog.select(contractType, date);

        BigDecimal sumBefore = draft.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() != InvoiceItemOrigin.CALCULATED)
                .map(ii -> BigDecimal.valueOf(ii.getRate()).multiply(BigDecimal.valueOf(ii.getHours())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal current = sumBefore;
        List<CalculationBreakdownLine> breakdown = new ArrayList<>();
        List<InvoiceItem> synthetic = new ArrayList<>();
        final int SYN_BASE_POS = Integer.MAX_VALUE - 1000; // keep synthetic lines at the very end
        int synIndex = 0;

        for (RuleStep step : ruleSet.stepsFor(date)) {
            BigDecimal base = (step.base == StepBase.SUM_BEFORE_DISCOUNTS) ? sumBefore : current;

            BigDecimal delta = BigDecimal.ZERO;
            String label = step.label;

            switch (step.type) {
                case PERCENT_DISCOUNT_ON_SUM -> {
                    BigDecimal pct = resolvePercent(step, contractTypeItems);
                    delta = base.multiply(pct).divide(BigDecimal.valueOf(100), SCALE + 2, RM).setScale(SCALE, RM).negate();
                    label = String.format("%s (%s%%)", step.label, pct.stripTrailingZeros().toPlainString());
                }
                case ADMIN_FEE_PERCENT -> {
                    BigDecimal pct = step.percent != null ? step.percent : BigDecimal.ZERO;
                    delta = base.multiply(pct).divide(BigDecimal.valueOf(100), SCALE + 2, RM).setScale(SCALE, RM).negate();
                    label = String.format("%s", step.label);
                }
                case FIXED_DEDUCTION -> {
                    BigDecimal amt = step.amount != null ? step.amount : BigDecimal.ZERO;
                    delta = amt.setScale(SCALE, RM).negate();
                }
                case GENERAL_DISCOUNT_PERCENT -> {
                    BigDecimal pct = draft.getHeaderDiscountPct() != null ? draft.getHeaderDiscountPct() : BigDecimal.ZERO;
                    if (pct.compareTo(BigDecimal.ZERO) > 0) {
                        delta = base.multiply(pct).divide(BigDecimal.valueOf(100), SCALE + 2, RM).setScale(SCALE, RM).negate();
                        label = String.format("%s (%s%%)", step.label, pct.stripTrailingZeros().toPlainString());
                    } else {
                        delta = BigDecimal.ZERO;
                    }
                }
                case ROUNDING -> {
                    BigDecimal unit = step.amount != null ? step.amount : BigDecimal.valueOf(0.01);
                    BigDecimal rounded = current.setScale(0, RoundingMode.UNNECESSARY); // placeholder — vi afrunder via SCALE ovenfor
                    delta = rounded.subtract(current);
                }
            }

            // Opdatér sum, breakdown og syntetisk linje (hvis delta != 0)
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                current = current.add(delta).setScale(SCALE, RM);

                CalculationBreakdownLine line = new CalculationBreakdownLine();
                line.ruleId = step.id;
                line.label = label;
                line.base = base.setScale(SCALE, RM);
                line.rateOrAmount = step.percent != null && step.type != RuleStepType.FIXED_DEDUCTION
                        ? step.percent : (step.type == RuleStepType.GENERAL_DISCOUNT_PERCENT
                        ? (draft.getHeaderDiscountPct() != null ? draft.getHeaderDiscountPct() : BigDecimal.ZERO) : step.amount);
                line.delta = delta;
                line.cumulative = current;
                breakdown.add(line);

                InvoiceItem synLine = syntheticLine(draft, step, label, delta);
                synLine.setPosition(SYN_BASE_POS + synIndex++);
                synthetic.add(synLine);
            }
        }

        BigDecimal sumAfter = current.max(BigDecimal.ZERO); // aldrig negativ total
        BigDecimal vatPct = draft.getVatPct() != null ? draft.getVatPct() : new BigDecimal("25.00");
        BigDecimal vatAmount = sumAfter.multiply(vatPct).divide(BigDecimal.valueOf(100), SCALE + 2, RM).setScale(SCALE, RM);
        BigDecimal grand = sumAfter.add(vatAmount).setScale(SCALE, RM);

        PriceResult pr = new PriceResult();
        pr.sumBeforeDiscounts = sumBefore.setScale(SCALE, RM);
        pr.sumAfterDiscounts = sumAfter;
        pr.vatAmount = vatAmount;
        pr.grandTotal = grand;
        pr.breakdown = breakdown;
        pr.syntheticItems = synthetic;

        registry.counter("invoice.pricing.applied_rules", "rules", String.valueOf(breakdown.size())).increment(); // Micrometer
        return pr;
    }

    private static InvoiceItem syntheticLine(Invoice draft, RuleStep step, String label, BigDecimal delta) {
        // Konvention: 1 x RATE (= ændringsbeløb). Delta er negativt => line.rate er negativ.
        InvoiceItem li = new InvoiceItem();
        li.setInvoiceuuid(draft.getUuid());
        li.setItemname(label);
        li.setDescription("");
        li.setHours(1.0);
        li.setRate(delta.doubleValue()); // delta er negativt for rabat
        li.setOrigin(InvoiceItemOrigin.CALCULATED);
        li.setRuleId(step.id);
        li.setLabel(label);
        li.setCalculationRef(UUID.randomUUID().toString());
        return li;
    }

    private static BigDecimal resolvePercent(RuleStep s, Map<String, String> cti) {
        if (s.paramKey != null && cti != null && cti.containsKey(s.paramKey)) {
            try {
                return new BigDecimal(cti.get(s.paramKey));
            } catch (Exception ignore) {}
        }
        return s.percent != null ? s.percent : BigDecimal.ZERO;
    }
}
