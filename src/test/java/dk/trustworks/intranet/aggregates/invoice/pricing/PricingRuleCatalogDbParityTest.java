package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed parity tests for the §9.7 fallback removal: with the V98/V397
 * seed-state rows present and ACTIVE in {@code pricing_rule_steps}, the
 * DB-only {@link PricingRuleCatalog} must produce exactly what the deleted
 * hardcoded rule sets produced, for all three legacy codes. The expected
 * values below are a verbatim encoding of the hardcoded sets that used to
 * live in the {@code PricingRuleCatalog} constructor.
 *
 * <p>Each test resets the legacy rows to seed state inside its own
 * {@code @TestTransaction} (rolled back), so drift in the shared dev database
 * — including the V396 retype of admin-fee rows — cannot skew the comparison.
 *
 * <p>Also covers §9.8 deterministic ordering: (priority, id) in SQL, with the
 * rule-id tie-break mirrored in the in-memory sorts.
 *
 * <p>Requires the local dev database (same as every other {@code @QuarkusTest}
 * in this repo).
 */
@QuarkusTest
class PricingRuleCatalogDbParityTest {

    private static final String C2021 = "SKI0217_2021";
    private static final String C2025 = "SKI0217_2025";
    private static final String C0215 = "SKI0215_2025";

    /** Date matching production invoice #70273 — inside every seed rule's validity (they are undated). */
    private static final LocalDate PROBE = LocalDate.of(2025, 11, 24);

    @Inject
    PricingRuleCatalog catalog;

    @Inject
    PricingEngine engine;

    @Test
    @TestTransaction
    void ski0217_2021_dbRules_matchOldHardcodedCatalog() {
        resetLegacySeedRows();

        RuleSet rs = catalog.select(C2021, PROBE);

        assertEquals(4, rs.steps.size(), "old hardcoded SKI0217_2021 set had 4 steps");
        assertStep(rs.steps.get(0), "ski21721-key", "SKI trapperabat",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS,
                null, null, "trapperabat", 10);
        assertStep(rs.steps.get(1), "ski21721-admin", "2% SKI administrationsgebyr",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                new BigDecimal("2"), null, null, 20);
        assertStep(rs.steps.get(2), "ski21721-fee", "Faktureringsgebyr",
                RuleStepType.FIXED_DEDUCTION, StepBase.CURRENT_SUM,
                null, new BigDecimal("2000"), null, 30);
        assertStep(rs.steps.get(3), "ski21721-general", "Generel rabat",
                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                null, null, null, 40);
    }

    @Test
    @TestTransaction
    void ski0217_2025_dbRules_matchOldHardcodedCatalog() {
        resetLegacySeedRows();

        RuleSet rs = catalog.select(C2025, PROBE);

        assertEquals(3, rs.steps.size(), "old hardcoded SKI0217_2025 set had 3 steps");
        assertStep(rs.steps.get(0), "ski21725-key", "SKI trapperabat",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS,
                null, null, "trapperabat", 10);
        assertStep(rs.steps.get(1), "ski21725-admin", "4% SKI administrationsgebyr",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                new BigDecimal("4"), null, null, 20);
        assertStep(rs.steps.get(2), "ski21725-general", "Generel rabat",
                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                null, null, null, 40);
    }

    @Test
    @TestTransaction
    void ski0215_2025_dbRules_matchOldHardcodedCatalog() {
        resetLegacySeedRows();

        RuleSet rs = catalog.select(C0215, PROBE);

        assertEquals(2, rs.steps.size(), "old hardcoded SKI0215_2025 set had 2 steps");
        assertStep(rs.steps.get(0), "ski21525-admin", "4% SKI administrationsgebyr",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                new BigDecimal("4"), null, null, 20);
        assertStep(rs.steps.get(1), "ski21525-general", "Generel rabat",
                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                null, null, null, 40);
    }

    /**
     * Engine-level parity via the real DB path, mirroring production invoice
     * #70273 (2025-11-24, trapperabat=11%) — the same expectations
     * {@code InvoiceItemRecalculatorTest} verifies against the in-memory rule
     * sets: 218 030.63 → −23 983.37 → −3 880.95 → −2 000.00 → 188 166.31.
     */
    @Test
    @TestTransaction
    void engineOutput_throughDbRules_matchesOldHardcodedOutput_forSki0217_2021() {
        resetLegacySeedRows();

        Invoice inv = invoice(C2021, PROBE,
                base("Consultant A", 1050.75, 95.5),
                base("Consultant B", 1050.75, 112.0));

        PriceResult pr = engine.price(inv, Map.of("trapperabat", "11"));

        assertEquals(3, pr.breakdown.size(), "trapperabat + admin + fixed fee (general discount is 0)");
        assertAll("invoice #70273 baseline through the DB",
                () -> assertEquals(0, pr.sumBeforeDiscounts.compareTo(new BigDecimal("218030.63"))),
                () -> assertEquals("ski21721-key", pr.breakdown.get(0).ruleId),
                () -> assertEquals(0, pr.breakdown.get(0).delta.compareTo(new BigDecimal("-23983.37"))),
                () -> assertEquals("ski21721-admin", pr.breakdown.get(1).ruleId),
                () -> assertEquals(0, pr.breakdown.get(1).delta.compareTo(new BigDecimal("-3880.95"))),
                () -> assertEquals("ski21721-fee", pr.breakdown.get(2).ruleId),
                () -> assertEquals(0, pr.breakdown.get(2).delta.compareTo(new BigDecimal("-2000.00"))),
                () -> assertEquals(0, pr.sumAfterDiscounts.compareTo(new BigDecimal("188166.31"))));
    }

    /**
     * §9.8: rules with equal priority order deterministically by id — SQL
     * ({@code ORDER BY priority, id}) and the in-memory catalog sort agree,
     * and the injected invoice-discount fallback always comes last.
     */
    @Test
    @TestTransaction
    void equalPriorityRules_orderDeterministically_sqlAndInMemory() {
        String code = "ZZORDER" + (System.nanoTime() % 1_000_000_000L);
        persistDefinition(code, "Ordering determinism test type");
        // insertion order == numeric id order == rule-id lexical order
        persistRule(code, "order-first", "First at 50",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.CURRENT_SUM,
                new BigDecimal("1.0"), null, null, 50);
        persistRule(code, "order-second", "Second at 50",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.CURRENT_SUM,
                new BigDecimal("2.0"), null, null, 50);
        persistRule(code, "order-early", "Early at 10",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.CURRENT_SUM,
                new BigDecimal("3.0"), null, null, 10);

        List<String> sqlOrder = PricingRuleStepEntity.findByContractTypeAndDate(code, PROBE).stream()
                .map(PricingRuleStepEntity::getRuleId)
                .toList();
        assertEquals(List.of("order-early", "order-first", "order-second"), sqlOrder,
                "SQL must order by (priority, id)");

        List<String> catalogOrder = catalog.select(code, PROBE).steps.stream()
                .map(s -> s.id)
                .toList();
        assertEquals(List.of("order-early", "order-first", "order-second",
                        PricingRuleCatalog.GENERAL_FALLBACK_RULE_ID), catalogOrder,
                "catalog must preserve the deterministic order and inject the fallback last");
    }

    // ── seed-state fixture (verbatim copy of the deleted hardcoded catalog) ────

    /**
     * Resets the three legacy codes to V98/V397 seed state: deletes whatever
     * the shared dev DB currently holds for them and re-inserts the 9 rules
     * exactly as the hardcoded catalog defined them (active, undated).
     * Runs inside the test's transaction — rolled back afterwards.
     */
    private void resetLegacySeedRows() {
        PricingRuleStepEntity.delete("contractTypeCode in ?1", List.of(C2021, C2025, C0215));

        ensureDefinition(C2021, "SKI Framework Agreement 2021");
        ensureDefinition(C2025, "SKI Framework Agreement 2025");
        ensureDefinition(C0215, "SKI Simplified Agreement 2025");

        // --- SKI0217_2021: trapperabat -> 2% admin -> 2000 fixed -> generel rabat
        persistRule(C2021, "ski21721-key", "SKI trapperabat",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS,
                null, null, "trapperabat", 10);
        persistRule(C2021, "ski21721-admin", "2% SKI administrationsgebyr",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                BigDecimal.valueOf(2), null, null, 20);
        persistRule(C2021, "ski21721-fee", "Faktureringsgebyr",
                RuleStepType.FIXED_DEDUCTION, StepBase.CURRENT_SUM,
                null, BigDecimal.valueOf(2000), null, 30);
        persistRule(C2021, "ski21721-general", "Generel rabat",
                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                null, null, null, 40);

        // --- SKI0217_2025: trapperabat -> 4% admin -> generel rabat
        persistRule(C2025, "ski21725-key", "SKI trapperabat",
                RuleStepType.PERCENT_DISCOUNT_ON_SUM, StepBase.SUM_BEFORE_DISCOUNTS,
                null, null, "trapperabat", 10);
        persistRule(C2025, "ski21725-admin", "4% SKI administrationsgebyr",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                BigDecimal.valueOf(4), null, null, 20);
        persistRule(C2025, "ski21725-general", "Generel rabat",
                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                null, null, null, 40);

        // --- SKI0215_2025: 4% admin -> generel rabat
        persistRule(C0215, "ski21525-admin", "4% SKI administrationsgebyr",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM,
                BigDecimal.valueOf(4), null, null, 20);
        persistRule(C0215, "ski21525-general", "Generel rabat",
                RuleStepType.GENERAL_DISCOUNT_PERCENT, StepBase.CURRENT_SUM,
                null, null, null, 40);
    }

    private void ensureDefinition(String code, String name) {
        if (!ContractTypeDefinition.existsByCode(code)) {
            persistDefinition(code, name);
        }
    }

    private void persistDefinition(String code, String name) {
        ContractTypeDefinition definition = new ContractTypeDefinition();
        definition.setCode(code);
        definition.setName(name);
        definition.persist();
    }

    @SuppressWarnings("deprecation") // ADMIN_FEE_PERCENT: seed-state rows predate the V396 retype
    private void persistRule(String code, String ruleId, String label,
                             RuleStepType type, StepBase base,
                             BigDecimal percent, BigDecimal amount, String paramKey, int priority) {
        PricingRuleStepEntity entity = new PricingRuleStepEntity();
        entity.setContractTypeCode(code);
        entity.setRuleId(ruleId);
        entity.setLabel(label);
        entity.setRuleStepType(type);
        entity.setStepBase(base);
        entity.setPercent(percent);
        entity.setAmount(amount);
        entity.setParamKey(paramKey);
        entity.setPriority(priority);
        entity.setActive(true);
        entity.persist();
    }

    // ── assertions + invoice builders ──────────────────────────────────────────

    private static void assertStep(RuleStep actual, String id, String label,
                                   RuleStepType type, StepBase base,
                                   BigDecimal percent, BigDecimal amount, String paramKey, int priority) {
        assertAll("step " + id,
                () -> assertEquals(id, actual.id),
                () -> assertEquals(label, actual.label),
                () -> assertEquals(type, actual.type),
                () -> assertEquals(base, actual.base),
                () -> assertBigDecimal(percent, actual.percent, "percent"),
                () -> assertBigDecimal(amount, actual.amount, "amount"),
                () -> assertEquals(paramKey, actual.paramKey),
                () -> assertEquals(priority, actual.priority),
                () -> assertNull(actual.validFrom, "seed rules are undated"),
                () -> assertNull(actual.validTo, "seed rules are undated"));
    }

    private static void assertBigDecimal(BigDecimal expected, BigDecimal actual, String field) {
        if (expected == null) {
            assertNull(actual, field + " must be null");
        } else {
            assertTrue(actual != null && expected.compareTo(actual) == 0,
                    field + " expected " + expected + " but was " + actual);
        }
    }

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
}
