package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RulePurpose;
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
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture-based tests for {@link PricingPreviewService} (spec §9.1 explain mode).
 *
 * <p>Rule values are exact copies of the production rows (prod-pricing-fixtures extract,
 * 2026-07-10): SKI0215_2025 (4% admin fee valid 2025-07-01 → 2026-01-01 exclusive, disabled
 * general-discount placement), NOVO_MSP_2025 (5% supplier discount from 2026-07-27 on
 * SUM_BEFORE_DISCOUNTS + 1.8% MSP fee on CURRENT_SUM), SKI0217_2025 (trapperabat param-key
 * rule + 4% admin fee) — seeded under test-unique codes so local DB drift cannot skew results.
 *
 * <p>Requires the local dev database (same as every other {@code @QuarkusTest} here);
 * all writes roll back via {@code @TestTransaction}.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class PricingPreviewServiceTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100000.00");

    @Inject
    PricingPreviewService service;

    // --- SKI0215_2025 fixture: admin fee window + exclusive validTo boundary ---

    @Test
    @TestTransaction
    void ski0215Fixture_adminFeeExecutes_insideWindow() {
        String code = seedSki0215Fixture();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 11, 15), null, 0.0));

        assertEquals(code, response.getContractTypeCode());
        assertEquals(LocalDate.of(2025, 11, 15), response.getInvoiceDate());
        assertEquals(0, new BigDecimal("100000.00").compareTo(response.getSumBeforeRules()));

        // Execution order incl. skipped: admin (20), disabled general (40), system fallback (9000)
        List<PricingPreviewStepDTO> steps = response.getSteps();
        assertEquals(3, steps.size());

        PricingPreviewStepDTO admin = steps.get(0);
        assertEquals("ski21525-admin", admin.getRuleId());
        assertTrue(admin.isExecuted());
        assertNull(admin.getSkipReason());
        assertEquals(PricingPreviewStepDTO.SOURCE_DB, admin.getSource());
        assertEquals("ADMIN_FEE", admin.getPurpose());
        assertEquals(StepBase.CURRENT_SUM, admin.getBase());
        assertEquals(PricingPreviewStepDTO.RESOLVED_FROM_RULE_PERCENT, admin.getResolvedFrom());
        assertEquals(0, new BigDecimal("4").compareTo(admin.getRateOrAmount()));
        assertEquals(0, new BigDecimal("-4000.00").compareTo(admin.getDelta()));
        assertEquals(0, new BigDecimal("96000.00").compareTo(admin.getCumulative()));

        PricingPreviewStepDTO disabledGeneral = steps.get(1);
        assertEquals("ski21525-general", disabledGeneral.getRuleId());
        assertFalse(disabledGeneral.isExecuted());
        assertEquals(PricingPreviewStepDTO.SKIP_DISABLED, disabledGeneral.getSkipReason());
        assertEquals(0, BigDecimal.ZERO.compareTo(disabledGeneral.getDelta()));
        assertEquals(0, new BigDecimal("96000.00").compareTo(disabledGeneral.getCumulative()));

        PricingPreviewStepDTO fallback = steps.get(2);
        assertEquals(PricingPreviewService.SYSTEM_FALLBACK_RULE_ID, fallback.getRuleId());
        assertEquals(PricingPreviewStepDTO.SOURCE_SYSTEM, fallback.getSource());
        assertEquals(RuleStepType.GENERAL_DISCOUNT_PERCENT, fallback.getType());
        assertNull(fallback.getPurpose());
        assertFalse(fallback.isExecuted());
        assertEquals(PricingPreviewStepDTO.SKIP_ZERO_EFFECT, fallback.getSkipReason());
        assertEquals("invoice discount is 0%", fallback.getSkipDetail());
        assertEquals(0, new BigDecimal("96000.00").compareTo(fallback.getCumulative()));

        assertEquals(0, new BigDecimal("96000.00").compareTo(response.getTotalBeforeVat()));
        assertFalse(response.isClampedAtZero());
        assertEquals(0, new BigDecimal("25").compareTo(response.getVatPct()));
        assertEquals(0, new BigDecimal("24000.00").compareTo(response.getVatAmount()));
        assertEquals(0, new BigDecimal("120000.00").compareTo(response.getGrandTotal()));
    }

    @Test
    @TestTransaction
    void ski0215Fixture_adminFeeExpired_onValidToDate_exclusiveBoundary() {
        String code = seedSki0215Fixture();

        // validTo = 2026-01-01 is EXCLUSIVE: the rule no longer applies on that exact date.
        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2026, 1, 1), null, 0.0));

        PricingPreviewStepDTO admin = response.getSteps().get(0);
        assertEquals("ski21525-admin", admin.getRuleId());
        assertFalse(admin.isExecuted());
        assertEquals(PricingPreviewStepDTO.SKIP_EXPIRED, admin.getSkipReason());
        assertTrue(admin.getSkipDetail().contains("2026-01-01"));
        assertTrue(admin.getSkipDetail().contains("exclusive"));
        assertEquals(0, BigDecimal.ZERO.compareTo(admin.getDelta()));

        // Nothing executed → total unchanged, honest "no deductions"
        assertEquals(0, new BigDecimal("100000.00").compareTo(response.getTotalBeforeVat()));
        assertEquals(0, new BigDecimal("125000.00").compareTo(response.getGrandTotal()));
    }

    @Test
    @TestTransaction
    void ski0215Fixture_adminFeeStillExecutes_onLastDayBeforeValidTo() {
        String code = seedSki0215Fixture();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 12, 31), null, 0.0));

        PricingPreviewStepDTO admin = response.getSteps().get(0);
        assertTrue(admin.isExecuted(), "2025-12-31 is the last day inside the exclusive validTo window");
        assertEquals(0, new BigDecimal("-4000.00").compareTo(admin.getDelta()));
    }

    @Test
    @TestTransaction
    void ski0215Fixture_adminFeeNotYetValid_beforeValidFrom() {
        String code = seedSki0215Fixture();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 6, 30), null, 0.0));

        PricingPreviewStepDTO admin = response.getSteps().get(0);
        assertFalse(admin.isExecuted());
        assertEquals(PricingPreviewStepDTO.SKIP_NOT_YET_VALID, admin.getSkipReason());
        assertTrue(admin.getSkipDetail().contains("2025-07-01"));
        assertEquals(0, new BigDecimal("100000.00").compareTo(response.getTotalBeforeVat()));
    }

    // --- NOVO_MSP_2025 fixture: CURRENT_SUM chaining + NOT_YET_VALID ---

    @Test
    @TestTransaction
    void novoFixture_mspFeeChainsOnCurrentSum_afterSupplierDiscount() {
        String code = seedNovoFixture();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2026, 8, 1), null, 0.0));

        List<PricingPreviewStepDTO> steps = response.getSteps();
        assertEquals(3, steps.size()); // supplier (10), msp-fee (41), fallback (9000)

        PricingPreviewStepDTO supplier = steps.get(0);
        assertEquals("tw-supplier-discount", supplier.getRuleId());
        assertTrue(supplier.isExecuted());
        assertEquals("DISCOUNT", supplier.getPurpose());
        assertEquals(StepBase.SUM_BEFORE_DISCOUNTS, supplier.getBase());
        assertEquals(0, new BigDecimal("-5000.00").compareTo(supplier.getDelta()));
        assertEquals(0, new BigDecimal("95000.00").compareTo(supplier.getCumulative()));

        // MSP fee is 1.8% of the RUNNING total (95,000), not the original sum
        PricingPreviewStepDTO mspFee = steps.get(1);
        assertEquals("msp-fee", mspFee.getRuleId());
        assertTrue(mspFee.isExecuted());
        assertEquals(StepBase.CURRENT_SUM, mspFee.getBase());
        assertEquals(0, new BigDecimal("-1710.00").compareTo(mspFee.getDelta()));
        assertEquals(0, new BigDecimal("93290.00").compareTo(mspFee.getCumulative()));

        assertEquals(0, new BigDecimal("93290.00").compareTo(response.getTotalBeforeVat()));
        assertEquals(0, new BigDecimal("23322.50").compareTo(response.getVatAmount()));
        assertEquals(0, new BigDecimal("116612.50").compareTo(response.getGrandTotal()));
    }

    @Test
    @TestTransaction
    void novoFixture_supplierDiscountNotYetValid_mspFeeRunsOnFullSum() {
        String code = seedNovoFixture();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2026, 7, 1), null, 0.0));

        PricingPreviewStepDTO supplier = response.getSteps().get(0);
        assertFalse(supplier.isExecuted());
        assertEquals(PricingPreviewStepDTO.SKIP_NOT_YET_VALID, supplier.getSkipReason());
        assertTrue(supplier.getSkipDetail().contains("2026-07-27"));

        PricingPreviewStepDTO mspFee = response.getSteps().get(1);
        assertTrue(mspFee.isExecuted());
        assertEquals(0, new BigDecimal("-1800.00").compareTo(mspFee.getDelta()));
        assertEquals(0, new BigDecimal("98200.00").compareTo(response.getTotalBeforeVat()));
    }

    // --- SKI0217_2025 fixture: trapperabat param-key resolution ---

    @Test
    @TestTransaction
    void ski0217Fixture_trapperabatResolvesFromContractTypeItems() {
        String code = seedSki0217Fixture();
        String contractUuid = UUID.randomUUID().toString();
        persistContractTypeItem(contractUuid, "trapperabat", "15");

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 11, 15), contractUuid, 0.0));

        PricingPreviewStepDTO key = response.getSteps().get(0);
        assertEquals("ski21725-key", key.getRuleId());
        assertTrue(key.isExecuted());
        assertEquals(PricingPreviewStepDTO.RESOLVED_FROM_PARAM_KEY, key.getResolvedFrom());
        assertEquals(0, new BigDecimal("15").compareTo(key.getRateOrAmount()));
        assertEquals(0, new BigDecimal("-15000.00").compareTo(key.getDelta()));
        assertEquals(0, new BigDecimal("85000.00").compareTo(key.getCumulative()));

        // 4% admin fee applies to the running total (85,000)
        PricingPreviewStepDTO admin = response.getSteps().get(1);
        assertEquals("ski21725-admin", admin.getRuleId());
        assertEquals(0, new BigDecimal("-3400.00").compareTo(admin.getDelta()));
        assertEquals(0, new BigDecimal("81600.00").compareTo(response.getTotalBeforeVat()));
    }

    @Test
    @TestTransaction
    void ski0217Fixture_trapperabatUnresolved_withoutContract_isZeroEffect() {
        String code = seedSki0217Fixture();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 11, 15), null, 0.0));

        PricingPreviewStepDTO key = response.getSteps().get(0);
        assertEquals("ski21725-key", key.getRuleId());
        assertFalse(key.isExecuted());
        assertEquals(PricingPreviewStepDTO.SKIP_ZERO_EFFECT, key.getSkipReason());
        assertTrue(key.getSkipDetail().contains("trapperabat"));
        assertNull(key.getRateOrAmount());
        assertNull(key.getResolvedFrom());

        // Only the admin fee bites: -4,000 on 100,000
        assertEquals(0, new BigDecimal("96000.00").compareTo(response.getTotalBeforeVat()));
    }

    // --- Invoice discount: system fallback + DB placement row ---

    @Test
    @TestTransaction
    void systemFallback_executes_whenDiscountGiven() {
        String code = uniqueCode();
        persistContractType(code); // no rules at all

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 11, 15), null, 10.0));

        assertEquals(1, response.getSteps().size());
        PricingPreviewStepDTO fallback = response.getSteps().get(0);
        assertEquals(PricingPreviewService.SYSTEM_FALLBACK_RULE_ID, fallback.getRuleId());
        assertEquals(PricingPreviewStepDTO.SOURCE_SYSTEM, fallback.getSource());
        assertTrue(fallback.isExecuted());
        assertEquals(PricingPreviewStepDTO.RESOLVED_FROM_INVOICE_DISCOUNT, fallback.getResolvedFrom());
        assertEquals(0, new BigDecimal("10").compareTo(fallback.getRateOrAmount()));
        assertEquals(0, new BigDecimal("-10000.00").compareTo(fallback.getDelta()));
        assertEquals(0, new BigDecimal("90000.00").compareTo(response.getTotalBeforeVat()));
    }

    @Test
    @TestTransaction
    void activeGeneralDiscountRow_positionsTheDiscount_noFallbackInjected() {
        String code = uniqueCode();
        persistContractType(code);
        // Discount placement row FIRST (priority 10), fee after it (priority 20):
        // the discount then reduces the base the fee is computed on.
        persistRule(code, "general-first", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT,
                StepBase.CURRENT_SUM, null, null, null, null, null, 10, true);
        persistRule(code, "fee-after", "2% gebyr", RuleStepType.ADMIN_FEE_PERCENT,
                StepBase.CURRENT_SUM, new BigDecimal("2"), null, null, null, null, 20, true);

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 11, 15), null, 10.0));

        List<PricingPreviewStepDTO> steps = response.getSteps();
        assertEquals(2, steps.size(), "no system fallback when an active in-window placement row exists");
        assertTrue(steps.stream().noneMatch(s -> PricingPreviewService.SYSTEM_FALLBACK_RULE_ID.equals(s.getRuleId())));

        PricingPreviewStepDTO general = steps.get(0);
        assertEquals("general-first", general.getRuleId());
        assertEquals(PricingPreviewStepDTO.SOURCE_DB, general.getSource());
        assertEquals(0, new BigDecimal("-10000.00").compareTo(general.getDelta()));

        PricingPreviewStepDTO fee = steps.get(1);
        assertEquals(0, new BigDecimal("-1800.00").compareTo(fee.getDelta())); // 2% of 90,000
        assertEquals(0, new BigDecimal("88200.00").compareTo(response.getTotalBeforeVat()));
    }

    // --- Clamp, fixed deduction, purpose column, unknown code ---

    @Test
    @TestTransaction
    void fixedDeductionLargerThanAmount_clampsAtZero_vatAfterClamp() {
        String code = uniqueCode();
        persistContractType(code);
        // Same shape as the prod SKI0217_2021 Faktureringsgebyr (fixed 2,000.00)
        persistRule(code, "invoice-fee", "Faktureringsgebyr", RuleStepType.FIXED_DEDUCTION,
                StepBase.CURRENT_SUM, null, new BigDecimal("2000.00"), null, null, null, 30, true);

        PricingPreviewResponse response = service.preview(code,
                request(new BigDecimal("1500.00"), LocalDate.of(2025, 11, 15), null, 0.0));

        PricingPreviewStepDTO fee = response.getSteps().get(0);
        assertTrue(fee.isExecuted());
        assertNull(fee.getPurpose()); // fixed deductions carry no purpose
        assertEquals(0, new BigDecimal("-2000.00").compareTo(fee.getDelta()));
        assertEquals(0, new BigDecimal("-500.00").compareTo(fee.getCumulative()));

        assertTrue(response.isClampedAtZero());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getTotalBeforeVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getVatAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getGrandTotal()));
    }

    @Test
    @TestTransaction
    void purposeColumn_winsOverTypeDerivedDefault() {
        String code = uniqueCode();
        persistContractType(code);
        PricingRuleStepEntity rule = persistRule(code, "tagged-discount", "Tagged rabat",
                RuleStepType.ADMIN_FEE_PERCENT, StepBase.CURRENT_SUM, new BigDecimal("3"),
                null, null, null, null, 20, true);
        rule.setPurpose(RulePurpose.DISCOUNT); // column says DISCOUNT although the legacy type implies ADMIN_FEE
        rule.persist();

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, LocalDate.of(2025, 11, 15), null, 0.0));

        assertEquals("DISCOUNT", response.getSteps().get(0).getPurpose());
    }

    @Test
    @TestTransaction
    void unknownContractType_throwsNotFound() {
        assertThrows(NotFoundException.class,
                () -> service.preview("ZZNOPE" + System.nanoTime() % 1_000_000L,
                        request(AMOUNT, LocalDate.of(2025, 11, 15), null, 0.0)));
    }

    @Test
    @TestTransaction
    void nullInvoiceDate_defaultsToToday() {
        String code = uniqueCode();
        persistContractType(code);

        PricingPreviewResponse response = service.preview(code, request(AMOUNT, null, null, 0.0));

        assertEquals(LocalDate.now(), response.getInvoiceDate());
    }

    // --- Fixture seeding (exact prod values under unique codes) ---

    /** SKI0215_2025 prod rows: 4% admin (2025-07-01 → 2026-01-01, prio 20) + disabled general (prio 40). */
    private String seedSki0215Fixture() {
        String code = uniqueCode();
        persistContractType(code);
        persistRule(code, "ski21525-admin", "4% SKI administrationsgebyr", RuleStepType.ADMIN_FEE_PERCENT,
                StepBase.CURRENT_SUM, new BigDecimal("4.0000"), null, null,
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 1, 1), 20, true);
        persistRule(code, "ski21525-general", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT,
                StepBase.CURRENT_SUM, null, null, null, null, null, 40, false);
        return code;
    }

    /** NOVO_MSP_2025 prod rows: 5% supplier discount (from 2026-07-27, prio 10) + 1.8% MSP fee (prio 41). */
    private String seedNovoFixture() {
        String code = uniqueCode();
        persistContractType(code);
        persistRule(code, "tw-supplier-discount", "Commercial Adjustment (-5%)", RuleStepType.PERCENT_DISCOUNT_ON_SUM,
                StepBase.SUM_BEFORE_DISCOUNTS, new BigDecimal("5.0000"), null, null,
                LocalDate.of(2026, 7, 27), null, 10, true);
        persistRule(code, "msp-fee", "MSP fee", RuleStepType.PERCENT_DISCOUNT_ON_SUM,
                StepBase.CURRENT_SUM, new BigDecimal("1.8000"), null, null, null, null, 41, true);
        return code;
    }

    /** SKI0217_2025 prod rows: trapperabat param rule (prio 10) + 4% admin (prio 20) + disabled general (prio 40). */
    private String seedSki0217Fixture() {
        String code = uniqueCode();
        persistContractType(code);
        persistRule(code, "ski21725-key", "SKI trapperabat", RuleStepType.PERCENT_DISCOUNT_ON_SUM,
                StepBase.SUM_BEFORE_DISCOUNTS, null, null, "trapperabat", null, null, 10, true);
        persistRule(code, "ski21725-admin", "4% SKI administrationsgebyr", RuleStepType.ADMIN_FEE_PERCENT,
                StepBase.CURRENT_SUM, new BigDecimal("4.0000"), null, null, null, null, 20, true);
        persistRule(code, "ski21725-general", "Generel rabat", RuleStepType.GENERAL_DISCOUNT_PERCENT,
                StepBase.CURRENT_SUM, null, null, null, null, null, 40, false);
        return code;
    }

    private void persistContractType(String code) {
        ContractTypeDefinition contractType = new ContractTypeDefinition();
        contractType.setCode(code);
        contractType.setName("Pricing preview test type");
        contractType.persist();
    }

    @SuppressWarnings("SameParameterValue")
    private PricingRuleStepEntity persistRule(String code, String ruleId, String label, RuleStepType type,
                                              StepBase base, BigDecimal percent, BigDecimal amount, String paramKey,
                                              LocalDate validFrom, LocalDate validTo, int priority, boolean active) {
        PricingRuleStepEntity rule = new PricingRuleStepEntity();
        rule.setContractTypeCode(code);
        rule.setRuleId(ruleId);
        rule.setLabel(label);
        rule.setRuleStepType(type);
        rule.setStepBase(base);
        rule.setPercent(percent);
        rule.setAmount(amount);
        rule.setParamKey(paramKey);
        rule.setValidFrom(validFrom);
        rule.setValidTo(validTo);
        rule.setPriority(priority);
        rule.setActive(active);
        rule.persist();
        return rule;
    }

    private void persistContractTypeItem(String contractUuid, String key, String value) {
        ContractTypeItem item = new ContractTypeItem();
        item.setContractuuid(contractUuid);
        item.setKey(key);
        item.setValue(value);
        item.persist();
    }

    private static PricingPreviewRequest request(BigDecimal amount, LocalDate invoiceDate, String contractUuid, Double discountPct) {
        PricingPreviewRequest request = new PricingPreviewRequest();
        request.setAmount(amount);
        request.setInvoiceDate(invoiceDate);
        request.setContractUuid(contractUuid);
        request.setDiscountPct(discountPct);
        return request;
    }

    private static String uniqueCode() {
        return "ZZPREVIEW" + (System.nanoTime() % 1_000_000_000L);
    }
}
