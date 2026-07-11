package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingRuleCatalog;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStep;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InvoiceItemRecalculator}.
 *
 * Pure JUnit + a tiny in-memory subclass that overrides the Panache static
 * call-out hooks. The {@link PricingEngine} is exercised for real against an
 * in-memory copy of the production {@code pricing_rule_steps} rule sets, so
 * each test mirrors the actual pricing pipeline a production invoice runs
 * through.
 *
 * <p>Test data is modeled on real CREATED invoices that priced correctly in
 * Nov/Dec 2025 (NOVO_MSP_2025 #18172, SKI0215_2025 #18046, SKI0217_2021 #70273,
 * SKI0217_2025 #18044). Together they exercise:
 * <ul>
 *   <li>{@code PERCENT_DISCOUNT_ON_SUM} on {@code SUM_BEFORE_DISCOUNTS} (NOVO MSP fee)</li>
 *   <li>{@code ADMIN_FEE_PERCENT} on {@code CURRENT_SUM} (SKI 4% / 2% admin)</li>
 *   <li>{@code FIXED_DEDUCTION} (SKI 2000 kr faktureringsgebyr)</li>
 *   <li>{@code PERCENT_DISCOUNT_ON_SUM} parameterised via {@code contract_type_items} (SKI trapperabat)</li>
 *   <li>Multi-line BASE invoices and rule chains where one step's output feeds the next</li>
 * </ul>
 */
class InvoiceItemRecalculatorTest {

    private static final double EPS = 0.005;

    /**
     * Regression for the production incident on 2026-05-03: percent-on-sum
     * discounts (NOVO_MSP_2025 1.8% MSP fee) were silently emitting no synthetic
     * line because {@code invoice.invoiceitems} was cleared before the
     * {@link PricingEngine} read it, leaving {@code sumBefore = 0}.
     *
     * Modeled on real invoice #18172 (2025-11-30): 1100 × 40 = 44 000 →
     * MSP fee = -792.
     */
    @Test
    void novo_msp_2025_emits_1_8_percent_msp_fee_line() {
        Invoice invoice = invoice("NOVO_MSP_2025", LocalDate.of(2025, 11, 30),
                base("Consultant", 1100.0, 40.0));

        runRecalc(invoice);

        InvoiceItem msp = singleSyntheticByLabel(invoice, "MSP fee");
        assertAll("NOVO MSP fee — invoice #18172 baseline",
                () -> assertEquals(-792.0, msp.getRate() * msp.getHours(), EPS),
                () -> assertEquals(44_000.0, invoice.sumBeforeDiscounts, EPS),
                () -> assertEquals(43_208.0, invoice.sumAfterDiscounts, EPS),
                () -> assertEquals(2, invoice.getInvoiceitems().size(),
                        "Invoice ends with BASE + synthetic line in the in-memory bag"));
    }

    /**
     * Regression guard: the engine must observe the BASE items the recalculator
     * just persisted. The bug fixed on 2026-05-03 cleared the in-memory bag
     * after {@code persist()} but before {@code price()}, hiding all BASE rows
     * from the engine. We assert the items are visible at the exact moment
     * {@link PricingEngine#price(Invoice, Map)} runs, not afterwards.
     */
    @Test
    void price_engine_sees_base_items_at_invocation_time() {
        Invoice invoice = invoice("NOVO_MSP_2025", LocalDate.of(2025, 11, 30),
                base("Consultant", 1100.0, 40.0));

        AtomicReference<List<InvoiceItem>> snapshot = new AtomicReference<>();
        PricingEngine spyEngine = new PricingEngine() {
            @Override
            public dk.trustworks.intranet.aggregates.invoice.pricing.PriceResult price(
                    Invoice draft, Map<String, String> ctx) {
                snapshot.set(new ArrayList<>(draft.getInvoiceitems()));
                return super.price(draft, ctx);
            }
        };
        injectInto(spyEngine, "catalog", new TestCatalog());
        injectInto(spyEngine, "registry", new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        InvoiceItemRecalculator recalc = newRecalc();
        recalc.pricingEngine = spyEngine;
        recalc.recalculateInvoiceItems(invoice);

        assertFalse(snapshot.get().isEmpty(),
                "PricingEngine.price() must see the freshly persisted BASE items — "
                + "otherwise sumBefore=0 and percent-on-sum discounts collapse to 0.");
        assertTrue(snapshot.get().stream()
                        .allMatch(i -> i.getOrigin() == InvoiceItemOrigin.BASE),
                "Only BASE items should be visible at pricing time (synthetic comes after).");
        assertEquals(44_000.0,
                snapshot.get().stream().mapToDouble(i -> i.getRate() * i.getHours()).sum(),
                EPS);
    }

    /**
     * SKI0215_2025: a 4% admin fee on the CURRENT_SUM. Modeled on invoice
     * #18046 (2025-11-01) which had two BASE lines totalling 131 642.50 →
     * admin fee = -5 265.70.
     */
    @Test
    void ski_0215_2025_emits_4_percent_admin_fee_on_current_sum() {
        Invoice invoice = invoice("SKI0215_2025", LocalDate.of(2025, 11, 1),
                base("Consultant A", 1535.0, 18.0),
                base("Consultant B", 1325.0, 78.5));

        runRecalc(invoice);

        InvoiceItem admin = singleSyntheticByLabel(invoice, "SKI administrationsgebyr");
        assertAll("SKI0215_2025 — invoice #18046 baseline",
                () -> assertEquals(-5_265.70, admin.getRate() * admin.getHours(), EPS),
                () -> assertEquals(131_642.50, invoice.sumBeforeDiscounts, EPS),
                () -> assertEquals(126_376.80, invoice.sumAfterDiscounts, EPS));
    }

    /**
     * SKI0217_2025: 4% admin fee on a 3-line invoice. Modeled on #18044
     * (2025-11-01): 52 530 + 143 325 + 44 032.50 = 239 887.50 → -9 595.50.
     *
     * Also asserts no SKI trapperabat is emitted when no
     * {@code contract_type_items} entry exists for {@code trapperabat}.
     */
    @Test
    void ski_0217_2025_emits_admin_fee_for_three_line_invoice() {
        Invoice invoice = invoice("SKI0217_2025", LocalDate.of(2025, 11, 1),
                base("Consultant A", 1545.0, 34.0),
                base("Consultant B", 1050.0, 136.5),
                base("Consultant C", 1545.0, 28.5));

        runRecalc(invoice);

        InvoiceItem admin = singleSyntheticByLabel(invoice, "SKI administrationsgebyr");
        assertAll("SKI0217_2025 — invoice #18044 baseline",
                () -> assertEquals(-9_595.50, admin.getRate() * admin.getHours(), EPS),
                () -> assertEquals(239_887.50, invoice.sumBeforeDiscounts, EPS),
                () -> assertEquals(230_292.0, invoice.sumAfterDiscounts, EPS),
                () -> assertTrue(invoice.getInvoiceitems().stream()
                                .filter(i -> i.getOrigin() == InvoiceItemOrigin.CALCULATED)
                                .noneMatch(i -> i.getLabel() != null && i.getLabel().contains("trapperabat")),
                        "trapperabat must not appear unless contract_type_items provides one"));
    }

    /**
     * SKI0217_2021: the heaviest rule chain — trapperabat (parameterised via
     * contract_type_items) → 2% admin → 2 000 fixed fee → optional general
     * discount. Modeled on real invoice #70273 (2025-11-24) which had:
     * 100 346.62 + 117 684 = 218 030.62, with trapperabat=11% from the
     * contract's {@code contract_type_items}.
     *
     * Expected synthetic lines (in priority order):
     * <ul>
     *   <li>SKI trapperabat (11%): -23 983.37</li>
     *   <li>2% SKI administrationsgebyr (on 218030.62 − 23983.37): -3 880.95</li>
     *   <li>Faktureringsgebyr: -2 000.00</li>
     * </ul>
     */
    @Test
    void ski_0217_2021_emits_trapperabat_admin_and_fixed_fee_chain() {
        Invoice invoice = invoice("SKI0217_2021", LocalDate.of(2025, 11, 24),
                base("Consultant A", 1050.75, 95.5),
                base("Consultant B", 1050.75, 112.0));
        // trapperabat is sourced from contract_type_items (param_key="trapperabat")
        Map<String, String> contractTypeItems = Map.of("trapperabat", "11");

        runRecalc(invoice, contractTypeItems);

        // 1050.75 × 95.5 + 1050.75 × 112 = 100346.625 + 117684 = 218030.625
        // Matches production #70273 modulo half-cent rounding (production stored 218030.62).
        assertAll("SKI0217_2021 — invoice #70273 baseline",
                () -> assertEquals(218_030.625, invoice.sumBeforeDiscounts, 0.01),
                () -> assertEquals(-23_983.37,
                        valueOfSyntheticContaining(invoice, "trapperabat"), 0.01),
                () -> assertEquals(-3_880.95,
                        valueOfSyntheticContaining(invoice, "administrationsgebyr"), 0.01),
                () -> assertEquals(-2_000.0,
                        valueOfSyntheticContaining(invoice, "Faktureringsgebyr"), EPS));
    }

    /**
     * A contract type with no rules (e.g. plain {@code PERIOD}) should not
     * crash — the recalculator should simply persist the BASE items and emit no
     * synthetic lines. Catches future regressions where the engine starts
     * insisting on at least one rule.
     */
    @Test
    void unknown_contract_type_persists_base_items_without_synthetic_lines() {
        Invoice invoice = invoice("PERIOD", LocalDate.of(2025, 12, 1),
                base("Consultant", 1200.0, 50.0));

        runRecalc(invoice);

        long calc = invoice.getInvoiceitems().stream()
                .filter(i -> i.getOrigin() == InvoiceItemOrigin.CALCULATED).count();
        assertEquals(0, calc, "PERIOD contract type has no pricing rules");
        assertEquals(60_000.0, invoice.sumBeforeDiscounts, EPS);
        assertEquals(60_000.0, invoice.sumAfterDiscounts, EPS);
    }

    // ── test infrastructure ────────────────────────────────────────────────────

    /**
     * Builds an {@link InvoiceItemRecalculator} with the Panache call-outs
     * intercepted: deletes are no-ops (no DB), persists buffer items in memory
     * so the test can inspect ordering, and {@code contract_type_items} returns
     * empty by default.
     */
    private static InvoiceItemRecalculator newRecalc() {
        InvoiceItemRecalculator r = new InvoiceItemRecalculator() {
            @Override
            protected void deleteItemsByInvoiceUuid(String invoiceUuid) { /* no-op */ }
            @Override
            protected void persistItems(Iterable<InvoiceItem> items) { /* no-op */ }
            @Override
            protected Map<String, String> loadContractTypeItems(String contractuuid) {
                return Map.of();
            }
        };
        r.em = mock(EntityManager.class);
        r.pricingEngine = realPricingEngine();
        return r;
    }

    private static PricingEngine realPricingEngine() {
        PricingEngine engine = new PricingEngine();
        injectInto(engine, "catalog", new TestCatalog());
        injectInto(engine, "registry", new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        return engine;
    }

    /**
     * Catalog stub that returns the production rule sets from
     * {@code pricing_rule_steps} in-memory, bypassing Panache. Mirrors the
     * Nov/Dec 2025 production data (V98-seeded, guaranteed by V397):
     * <pre>
     *   NOVO_MSP_2025  → msp-fee 1.8% PERCENT_DISCOUNT_ON_SUM   priority 41
     *   SKI0215_2025   → 4% ADMIN_FEE_PERCENT on CURRENT_SUM    priority 20
     *   SKI0217_2025   → trapperabat (paramKey) + 4% admin      priorities 10, 20
     *   SKI0217_2021   → trapperabat + 2% admin + 2 000 fixed   priorities 10, 20, 30
     * </pre>
     * Anything else (e.g. PERIOD) yields no rules — {@link PricingRuleCatalog}
     * no longer carries a hardcoded fallback (spec §9.7), so the SKI rule sets
     * must be supplied here just like the DB supplies them in production.
     */
    private static final class TestCatalog extends PricingRuleCatalog {
        @Override
        protected List<RuleStep> loadFromDatabaseByCode(String code, LocalDate date) {
            return switch (code) {
                case "NOVO_MSP_2025" -> List.of(
                        rule("msp-fee", "MSP fee",
                                RuleStepType.PERCENT_DISCOUNT_ON_SUM,
                                StepBase.SUM_BEFORE_DISCOUNTS,
                                new BigDecimal("1.8000"), null, null, 41));
                case "SKI0215_2025" -> List.of(
                        rule("ski21525-admin", "4% SKI administrationsgebyr",
                                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                                new BigDecimal("4.0000"), null, null, 20),
                        rule("ski21525-general", "Generel rabat",
                                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                                null, null, null, 40));
                case "SKI0217_2025" -> List.of(
                        rule("ski21725-key", "SKI trapperabat",
                                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS,
                                null, null, "trapperabat", 10),
                        rule("ski21725-admin", "4% SKI administrationsgebyr",
                                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                                new BigDecimal("4.0000"), null, null, 20),
                        rule("ski21725-general", "Generel rabat",
                                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                                null, null, null, 40));
                case "SKI0217_2021" -> List.of(
                        rule("ski21721-key", "SKI trapperabat",
                                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS,
                                null, null, "trapperabat", 10),
                        rule("ski21721-admin", "2% SKI administrationsgebyr",
                                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                                new BigDecimal("2.0000"), null, null, 20),
                        rule("ski21721-fee", "Faktureringsgebyr",
                                RuleStepType.FIXED_DEDUCTION, StepBase.CURRENT_SUM,
                                null, new BigDecimal("2000.00"), null, 30),
                        rule("ski21721-general", "Generel rabat",
                                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                                null, null, null, 40));
                default -> List.of();
            };
        }

        private static RuleStep rule(String id, String label, RuleStepType type, StepBase base,
                                     BigDecimal percent, BigDecimal amount, String paramKey, int priority) {
            RuleStep s = new RuleStep();
            s.id = id; s.label = label; s.type = type; s.base = base;
            s.percent = percent; s.amount = amount; s.paramKey = paramKey;
            s.priority = priority;
            return s;
        }
    }

    private static void runRecalc(Invoice invoice) {
        runRecalc(invoice, Map.of());
    }

    private static void runRecalc(Invoice invoice, Map<String, String> contractTypeItems) {
        InvoiceItemRecalculator r = new InvoiceItemRecalculator() {
            @Override protected void deleteItemsByInvoiceUuid(String uuid) { /* no-op */ }
            @Override protected void persistItems(Iterable<InvoiceItem> items) { /* no-op */ }
            @Override protected Map<String, String> loadContractTypeItems(String c) { return contractTypeItems; }
        };
        r.em = mock(EntityManager.class);
        r.pricingEngine = realPricingEngine();
        r.recalculateInvoiceItems(invoice);
    }

    private static InvoiceItem singleSyntheticByLabel(Invoice invoice, String labelPart) {
        List<InvoiceItem> matches = invoice.getInvoiceitems().stream()
                .filter(i -> i.getOrigin() == InvoiceItemOrigin.CALCULATED)
                .filter(i -> i.getLabel() != null && i.getLabel().contains(labelPart))
                .toList();
        assertEquals(1, matches.size(),
                "Expected exactly one synthetic line with label containing \""
                + labelPart + "\", found " + matches.size());
        return matches.get(0);
    }

    private static double valueOfSyntheticContaining(Invoice invoice, String labelPart) {
        InvoiceItem ii = singleSyntheticByLabel(invoice, labelPart);
        return ii.getRate() * ii.getHours();
    }

    // ── invoice + line builders ────────────────────────────────────────────────

    private static InvoiceItem base(String name, double rate, double hours) {
        InvoiceItem ii = new InvoiceItem();
        ii.setUuid(UUID.randomUUID().toString());
        ii.setItemname(name);
        ii.setRate(rate);
        ii.setHours(hours);
        ii.setPosition(1);
        ii.setOrigin(InvoiceItemOrigin.BASE);
        return ii;
    }

    private static Invoice invoice(String contractType, LocalDate date, InvoiceItem... bases) {
        Invoice inv = new Invoice();
        inv.setUuid(UUID.randomUUID().toString());
        inv.contractType = contractType;
        inv.setContractuuid(UUID.randomUUID().toString());
        inv.setInvoicedate(date);
        inv.setVat(25.0);
        inv.setDiscount(0.0);
        inv.invoiceitems = new ArrayList<>();
        for (InvoiceItem b : bases) {
            b.setInvoiceuuid(inv.getUuid());
            inv.invoiceitems.add(b);
        }
        return inv;
    }

    /**
     * Reflective field setter — the {@link PricingEngine} resolves
     * {@code catalog} and {@code registry} via CDI which we don't run here.
     * Walks the class hierarchy so anonymous subclasses (used by the
     * "snapshot at price()" test) still find the inherited fields.
     */
    private static void injectInto(Object target, String fieldName, Object value) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to inject " + fieldName, e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
