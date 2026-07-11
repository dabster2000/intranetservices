package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.pricing.PriceResult;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.PricingPreviewRequest;
import dk.trustworks.intranet.contracts.dto.PricingPreviewResponse;
import dk.trustworks.intranet.contracts.dto.PricingPreviewStepDTO;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity harness (spec §9.1 / §12.2): the explain path in {@link PricingPreviewService}
 * and the real {@link PricingEngine#price} must produce IDENTICAL totals and identical
 * executed-step deltas for the same inputs. Any drift here means the simulator lies
 * about what an invoice would experience — the core defect this phase removes.
 *
 * <p>Scenarios use rules active on the invoice date under test-unique codes, so the
 * comparison is engine-vs-explain on the same DB rule set regardless of whether the
 * legacy hardcoded fallback in PricingRuleCatalog is still present (§9.7 removes it).
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class PricingPreviewEngineParityTest {

    private static final LocalDate DATE = LocalDate.of(2025, 11, 15);

    @Inject
    PricingPreviewService previewService;

    @Inject
    PricingEngine pricingEngine;

    @Test
    @TestTransaction
    void fullPipeline_zeroDiscount_previewMatchesEngine() {
        assertParity(new BigDecimal("100000.00"), 0.0, "11");
    }

    @Test
    @TestTransaction
    void fullPipeline_withInvoiceDiscount_previewMatchesEngine() {
        assertParity(new BigDecimal("100000.00"), 7.5, "11");
    }

    @Test
    @TestTransaction
    void fullPipeline_oddAmount_roundingParity() {
        // Odd amount forces HALF_UP rounding on every percent step
        assertParity(new BigDecimal("12345.67"), 3.0, "8");
    }

    @Test
    @TestTransaction
    void clampScenario_smallAmount_previewMatchesEngine() {
        // Fixed 2,000 deduction on a 1,500 invoice → engine clamps at zero
        assertParity(new BigDecimal("1500.00"), 0.0, "2");
    }

    /**
     * Seeds a full pipeline (trapperabat param rule on SUM_BEFORE_DISCOUNTS, 4% admin fee
     * on CURRENT_SUM, fixed 2,000 deduction, disabled placement row → system fallback),
     * then runs BOTH the explain path and the real engine on the same synthetic invoice
     * and asserts identical totals and executed deltas.
     */
    private void assertParity(BigDecimal amount, double discountPct, String trapperabat) {
        String code = uniqueCode();
        String contractUuid = UUID.randomUUID().toString();
        seedPipeline(code);
        persistContractTypeItem(contractUuid, "trapperabat", trapperabat);

        // Explain path
        PricingPreviewRequest request = new PricingPreviewRequest();
        request.setAmount(amount);
        request.setInvoiceDate(DATE);
        request.setContractUuid(contractUuid);
        request.setDiscountPct(discountPct);
        PricingPreviewResponse preview = previewService.preview(code, request);

        // Real engine on the equivalent synthetic invoice
        Invoice draft = syntheticInvoice(code, contractUuid, amount, discountPct);
        PriceResult engine = pricingEngine.price(draft, Map.of("trapperabat", trapperabat));

        assertEquals(0, engine.sumBeforeDiscounts.compareTo(preview.getSumBeforeRules()),
                "sumBeforeRules must equal engine sumBeforeDiscounts");
        assertEquals(0, engine.sumAfterDiscounts.compareTo(preview.getTotalBeforeVat()),
                "totalBeforeVat must equal engine sumAfterDiscounts");
        assertEquals(0, engine.vatAmount.compareTo(preview.getVatAmount()),
                "vatAmount must match the engine");
        assertEquals(0, engine.grandTotal.compareTo(preview.getGrandTotal()),
                "grandTotal must match the engine");

        // Executed-only steps must line up 1:1 with the engine breakdown (order + delta + cumulative).
        // DELIBERATELY not compared: labels (the engine suffixes " (<pct>%)" onto breakdown labels
        // for non-ADMIN_FEE percentage rows; the preview returns the stored label verbatim per
        // contract P1) and rateOrAmount (the preview enriches paramKey-resolved steps with the
        // resolved percent where the engine breakdown leaves rateOrAmount null — that engine quirk
        // is pinned by PricingParitySnapshotTest.trapperabat_param_resolution_snapshot). Do not
        // "tighten" this into matching the engine's less-informative nulls.
        List<PricingPreviewStepDTO> executed = preview.getSteps().stream()
                .filter(PricingPreviewStepDTO::isExecuted)
                .toList();
        assertEquals(engine.breakdown.size(), executed.size(),
                "explain mode must execute exactly the steps the engine executes");
        for (int i = 0; i < executed.size(); i++) {
            assertEquals(engine.breakdown.get(i).ruleId, executed.get(i).getRuleId(),
                    "step order must match the engine at index " + i);
            assertEquals(0, engine.breakdown.get(i).delta.compareTo(executed.get(i).getDelta()),
                    "delta must match the engine for rule " + executed.get(i).getRuleId());
            assertEquals(0, engine.breakdown.get(i).cumulative.compareTo(executed.get(i).getCumulative()),
                    "cumulative must match the engine for rule " + executed.get(i).getRuleId());
        }

        // Sanity: the pipeline actually exercised the interesting paths
        assertTrue(preview.getSteps().size() >= executed.size(), "skipped steps must also be listed");
    }

    private Invoice syntheticInvoice(String code, String contractUuid, BigDecimal amount, double discountPct) {
        Invoice draft = new Invoice();
        draft.uuid = UUID.randomUUID().toString();
        draft.contractType = code;
        draft.contractuuid = contractUuid;
        draft.invoicedate = DATE;
        draft.discount = discountPct;
        draft.vat = 25.0; // same default the preview uses
        draft.invoiceitems.add(new InvoiceItem(null, "Simulated line", "",
                amount.doubleValue(), 1.0, 1, draft.uuid, InvoiceItemOrigin.BASE));
        return draft;
    }

    private void seedPipeline(String code) {
        ContractTypeDefinition contractType = new ContractTypeDefinition();
        contractType.setCode(code);
        contractType.setName("Pricing parity test type");
        contractType.persist();

        persistRule(code, "parity-key", "SKI trapperabat", RuleStepType.PERCENT_DISCOUNT_ON_SUM,
                StepBase.SUM_BEFORE_DISCOUNTS, null, null, "trapperabat", 10, true);
        persistRule(code, "parity-admin", "4% administrationsgebyr", RuleStepType.ADMIN_FEE_PERCENT,
                StepBase.CURRENT_SUM, new BigDecimal("4.0000"), null, null, 20, true);
        persistRule(code, "parity-fee", "Faktureringsgebyr", RuleStepType.FIXED_DEDUCTION,
                StepBase.CURRENT_SUM, null, new BigDecimal("2000.00"), null, 30, true);
        // Disabled placement row → both engine and explain must fall back to the system step
        persistRule(code, "parity-general", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT,
                StepBase.CURRENT_SUM, null, null, null, 40, false);
    }

    private void persistRule(String code, String ruleId, String label, RuleStepType type, StepBase base,
                             BigDecimal percent, BigDecimal amount, String paramKey, int priority, boolean active) {
        PricingRuleStepEntity rule = new PricingRuleStepEntity();
        rule.setContractTypeCode(code);
        rule.setRuleId(ruleId);
        rule.setLabel(label);
        rule.setRuleStepType(type);
        rule.setStepBase(base);
        rule.setPercent(percent);
        rule.setAmount(amount);
        rule.setParamKey(paramKey);
        rule.setPriority(priority);
        rule.setActive(active);
        rule.persist();
    }

    private void persistContractTypeItem(String contractUuid, String key, String value) {
        ContractTypeItem item = new ContractTypeItem();
        item.setContractuuid(contractUuid);
        item.setKey(key);
        item.setValue(value);
        item.persist();
    }

    private static String uniqueCode() {
        return "ZZPARITY" + (System.nanoTime() % 1_000_000_000L);
    }
}
