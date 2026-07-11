package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.BulkCreateRulesRequest;
import dk.trustworks.intranet.contracts.dto.CreateRuleStepRequest;
import dk.trustworks.intranet.contracts.dto.PricingRuleStepDTO;
import dk.trustworks.intranet.contracts.dto.UpdateRuleStepRequest;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-backed tests for pricing-rule date-order validation (spec §9.5).
 *
 * Creating or updating a pricing rule with {@code validFrom >= validTo} must surface as
 * a 400 ({@link BadRequestException}) with a message naming both fields — previously the
 * entity lifecycle {@code @PrePersist}/{@code @PreUpdate} threw a bare
 * {@code IllegalArgumentException} that surfaced as an unhandled 500.
 *
 * Requires the local dev database (same requirement as every other {@code @QuarkusTest}
 * in this repo); all writes roll back via {@code @TestTransaction}.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"contracts:read", "contracts:write"})
class PricingRuleDateOrderValidationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate BEFORE_FROM = LocalDate.of(2025, 12, 1);

    @Inject
    PricingRuleStepService pricingRuleService;

    @Test
    @TestTransaction
    void create_validFromAfterValidTo_returns400NamingBothFields() {
        String code = uniqueCode();
        persistContractType(code);

        CreateRuleStepRequest request = createRequest("date-order");
        request.setValidFrom(FROM);
        request.setValidTo(BEFORE_FROM);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> pricingRuleService.createRule(code, request));
        assertBadRequestNamesDateFields(ex);
    }

    @Test
    @TestTransaction
    void create_validFromEqualToValidTo_returns400() {
        String code = uniqueCode();
        persistContractType(code);

        CreateRuleStepRequest request = createRequest("date-order");
        request.setValidFrom(FROM);
        request.setValidTo(FROM);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> pricingRuleService.createRule(code, request));
        assertBadRequestNamesDateFields(ex);
    }

    @Test
    @TestTransaction
    void bulkCreate_validFromAfterValidTo_returns400() {
        String code = uniqueCode();
        persistContractType(code);

        CreateRuleStepRequest bad = createRequest("date-order");
        bad.setValidFrom(FROM);
        bad.setValidTo(BEFORE_FROM);
        BulkCreateRulesRequest bulk = new BulkCreateRulesRequest();
        bulk.setRules(List.of(bad));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> pricingRuleService.createRulesBulk(code, bulk));
        assertBadRequestNamesDateFields(ex);
    }

    @Test
    @TestTransaction
    void update_validFromAfterValidTo_returns400_andLeavesRuleUnchanged() {
        String code = uniqueCode();
        persistContractType(code);
        pricingRuleService.createRule(code, createRequest("date-order"));

        UpdateRuleStepRequest update = updateRequest();
        update.setValidFrom(FROM);
        update.setValidTo(BEFORE_FROM);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> pricingRuleService.updateRule(code, "date-order", update));
        assertBadRequestNamesDateFields(ex);
    }

    @Test
    @TestTransaction
    void create_validDateOrder_succeeds() {
        String code = uniqueCode();
        persistContractType(code);

        CreateRuleStepRequest request = createRequest("date-order");
        request.setValidFrom(BEFORE_FROM);
        request.setValidTo(FROM);

        PricingRuleStepDTO created = pricingRuleService.createRule(code, request);
        assertNotNull(created.getId());
        assertEquals(BEFORE_FROM, created.getValidFrom());
        assertEquals(FROM, created.getValidTo());
    }

    @Test
    @TestTransaction
    void create_openEndedDates_succeed() {
        String code = uniqueCode();
        persistContractType(code);

        // Only validFrom set
        CreateRuleStepRequest fromOnly = createRequest("from-only");
        fromOnly.setValidFrom(FROM);
        assertNotNull(pricingRuleService.createRule(code, fromOnly).getId());

        // Only validTo set
        CreateRuleStepRequest toOnly = createRequest("to-only");
        toOnly.setValidTo(FROM);
        assertNotNull(pricingRuleService.createRule(code, toOnly).getId());
    }

    private void assertBadRequestNamesDateFields(BadRequestException ex) {
        assertEquals(400, ex.getResponse().getStatus(), "date-order violation must map to 400, not 500");
        String message = ex.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("validFrom"), "error message must name the validFrom field: " + message);
        assertTrue(message.contains("validTo"), "error message must name the validTo field: " + message);
    }

    private CreateRuleStepRequest createRequest(String ruleId) {
        CreateRuleStepRequest request = new CreateRuleStepRequest();
        request.setRuleId(ruleId);
        request.setLabel("Date-order test pricing rule");
        request.setRuleStepType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        request.setStepBase(StepBase.SUM_BEFORE_DISCOUNTS);
        request.setPercent(new BigDecimal("5.00"));
        request.setPriority(10);
        return request;
    }

    private UpdateRuleStepRequest updateRequest() {
        UpdateRuleStepRequest request = new UpdateRuleStepRequest();
        request.setLabel("Date-order test pricing rule (updated)");
        request.setRuleStepType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        request.setStepBase(StepBase.SUM_BEFORE_DISCOUNTS);
        request.setPercent(new BigDecimal("6.00"));
        request.setPriority(10);
        return request;
    }

    private void persistContractType(String code) {
        ContractTypeDefinition contractType = new ContractTypeDefinition();
        contractType.setCode(code);
        contractType.setName("Date-order test type");
        contractType.persist();
    }

    private String uniqueCode() {
        return "ZZDATEORD" + (System.nanoTime() % 1_000_000_000L);
    }
}
