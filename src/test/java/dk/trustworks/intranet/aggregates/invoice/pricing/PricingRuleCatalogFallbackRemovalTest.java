package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the §9.7 fallback removal: {@link PricingRuleCatalog} no longer
 * carries hardcoded legacy rule sets, so "zero active-on-date DB rules"
 * honestly means "no deductions" — only the system-injected invoice-discount
 * step remains.
 *
 * <p><b>DOCUMENTED BEHAVIOR CHANGE (accepted by spec §9.7):</b> before this
 * change, whenever {@code pricing_rule_steps} yielded zero rows that were both
 * {@code active=1} and date-valid for a legacy code, the catalog silently
 * resurrected the hardcoded rule set. Windows where that actually happened,
 * per the 2026-07-10 production extract:
 * <ul>
 *   <li><b>SKI0215_2025</b> — its only active rule (ski21525-admin, 4%) is
 *       date-bounded [2025-07-01, 2026-01-01) and ski21525-general is
 *       inactive. For invoice dates ≥ 2026-01-01 (all current invoices!) or
 *       &lt; 2025-07-01 the old code applied the undated hardcoded 4% admin
 *       fee anyway: 100 000.00 → 96 000.00. New behavior: 100 000.00 →
 *       100 000.00 — the fee's DB expiry now actually takes effect.</li>
 *   <li><b>SKI0217_2021 / SKI0217_2025</b> — all prod deduction rules are
 *       undated and active, so the fallback never fired for real invoices;
 *       the change only matters if every rule is deactivated, which now
 *       honestly yields no deductions instead of resurrecting the hardcoded
 *       chain (trapperabat → admin fee → fixed fee).</li>
 * </ul>
 * The with-rules parity direction (DB rules active ⇒ output identical to the
 * old hardcoded sets) lives in {@link PricingRuleCatalogDbParityTest}.
 */
class PricingRuleCatalogFallbackRemovalTest {

    /** A date inside the window where the old fallback fired for SKI0215_2025 in prod. */
    private static final LocalDate D = LocalDate.of(2026, 2, 1);

    /** Catalog whose DB yields zero active-on-date rules — the exact pre-change fallback trigger. */
    private static final class EmptyDbCatalog extends PricingRuleCatalog {
        @Override
        protected List<RuleStep> loadFromDatabaseByCode(String code, LocalDate date) {
            return List.of();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKI0217_2021", "SKI0217_2025", "SKI0215_2025"})
    void legacyCode_withNoActiveDbRules_yieldsOnlyTheInjectedInvoiceDiscountStep(String code) {
        RuleSet rs = new EmptyDbCatalog().select(code, D);

        assertEquals(1, rs.steps.size(),
                "no active DB rules must mean no deductions — the hardcoded fallback is gone (§9.7)");
        RuleStep only = rs.steps.get(0);
        assertAll("injected invoice-discount fallback",
                () -> assertEquals(PricingRuleCatalog.GENERAL_FALLBACK_RULE_ID, only.id),
                () -> assertEquals(RuleStepType.GENERAL_DISCOUNT_PERCENT, only.type),
                () -> assertEquals(StepBase.CURRENT_SUM, only.base),
                () -> assertEquals(PricingRuleCatalog.GENERAL_FALLBACK_PRIORITY, only.priority));
    }

    /**
     * SKI0215_2025 on 2026-02-01 (fee expired in DB): the old code deducted the
     * hardcoded 4% admin fee (100 000 → 96 000); now nothing is deducted.
     */
    @Test
    void ski0215_2025_afterFeeExpiry_noDeductions_insteadOfResurrectedFourPercent() {
        Invoice inv = invoice("SKI0215_2025", D, 0.0, base(1000.0, 100.0)); // 100 000.00

        PriceResult pr = engine().price(inv, Map.of());

        assertAll("honest pricing after fallback removal",
                () -> assertEquals(0, pr.sumBeforeDiscounts.compareTo(new BigDecimal("100000.00"))),
                () -> assertEquals(0, pr.sumAfterDiscounts.compareTo(new BigDecimal("100000.00")),
                        "old behavior deducted the resurrected hardcoded 4% (→ 96 000.00)"),
                () -> assertTrue(pr.breakdown.isEmpty(), "no rules ⇒ empty breakdown"),
                () -> assertTrue(pr.syntheticItems.isEmpty(), "no rules ⇒ no synthetic lines"));
    }

    /** The injected fallback still prices invoice.discount when it is set. */
    @Test
    void invoiceDiscount_stillApplies_throughTheInjectedFallback() {
        Invoice inv = invoice("SKI0215_2025", D, 10.0, base(1000.0, 100.0)); // 100 000, 10% discount

        PriceResult pr = engine().price(inv, Map.of());

        assertEquals(1, pr.breakdown.size(), "only the invoice-discount step runs");
        assertAll("invoice discount via general-fallback",
                () -> assertEquals(PricingRuleCatalog.GENERAL_FALLBACK_RULE_ID, pr.breakdown.get(0).ruleId),
                () -> assertEquals(0, pr.breakdown.get(0).delta.compareTo(new BigDecimal("-10000.00"))),
                () -> assertEquals(0, pr.sumAfterDiscounts.compareTo(new BigDecimal("90000.00"))));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static PricingEngine engine() {
        PricingEngine engine = new PricingEngine();
        engine.catalog = new EmptyDbCatalog();
        engine.registry = new SimpleMeterRegistry();
        return engine;
    }

    private static InvoiceItem base(double rate, double hours) {
        InvoiceItem ii = new InvoiceItem();
        ii.setUuid(UUID.randomUUID().toString());
        ii.setItemname("Consultant");
        ii.setRate(rate);
        ii.setHours(hours);
        ii.setPosition(1);
        ii.setOrigin(InvoiceItemOrigin.BASE);
        return ii;
    }

    private static Invoice invoice(String contractType, LocalDate date, double discount, InvoiceItem... bases) {
        Invoice inv = new Invoice();
        inv.setUuid(UUID.randomUUID().toString());
        inv.contractType = contractType;
        inv.setContractuuid(UUID.randomUUID().toString());
        inv.setInvoicedate(date);
        inv.setVat(25.0);
        inv.setDiscount(discount);
        inv.invoiceitems = new ArrayList<>();
        for (InvoiceItem b : bases) {
            b.setInvoiceuuid(inv.getUuid());
            inv.invoiceitems.add(b);
        }
        return inv;
    }
}
