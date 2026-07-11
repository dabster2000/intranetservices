package dk.trustworks.intranet.contracts.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.dto.PricingPreviewRequest;
import dk.trustworks.intranet.contracts.dto.PricingPreviewResponse;
import dk.trustworks.intranet.contracts.dto.PricingPreviewStepDTO;
import dk.trustworks.intranet.contracts.services.PricingPreviewService;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pricing simulation endpoint (spec §9.1):
 * POST /api/contract-types/{code}/pricing-preview
 * Must return 200 with the explain-mode breakdown and surface 404 for unknown codes.
 */
class ContractTypePricingPreviewResourceTest {

    private static final String CODE = "SKI0215_2025";

    @Test
    void preview_returns200WithBreakdown() {
        ContractTypePricingPreviewResource resource = new ContractTypePricingPreviewResource();
        PricingPreviewService service = mock(PricingPreviewService.class);
        resource.pricingPreviewService = service;

        PricingPreviewRequest request = new PricingPreviewRequest();
        request.setAmount(new BigDecimal("100000.00"));
        request.setInvoiceDate(LocalDate.of(2025, 11, 15));
        request.setDiscountPct(0.0);

        PricingPreviewResponse breakdown = new PricingPreviewResponse();
        breakdown.setContractTypeCode(CODE);
        breakdown.setTotalBeforeVat(new BigDecimal("96000.00"));
        when(service.preview(CODE, request)).thenReturn(breakdown);

        Response response = resource.preview(CODE, request);

        assertEquals(200, response.getStatus());
        assertSame(breakdown, response.getEntity());
    }

    /**
     * Pins the wire contract (P1 / spec §9.1): serializing the response must produce
     * EXACTLY the pinned field names — 9 top-level fields and 13 per step — with
     * enum values as their names and null fields present (skipReason/skipDetail are
     * "null when executed", not omitted). Mirrors the production mapper config
     * ({@code JavaTimeObjectMapperCustomizer}: JavaTimeModule + ISO dates).
     */
    @Test
    void previewResponse_serializesWithPinnedP1FieldNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        PricingPreviewStepDTO step = new PricingPreviewStepDTO();
        step.setRuleId("ski21525-admin");
        step.setLabel("4% SKI administrationsgebyr");
        step.setType(RuleStepType.PERCENT_DISCOUNT_ON_SUM);
        step.setPurpose("ADMIN_FEE");
        step.setSource(PricingPreviewStepDTO.SOURCE_DB);
        step.setBase(StepBase.SUM_BEFORE_DISCOUNTS);
        step.setRateOrAmount(new BigDecimal("4.00"));
        step.setResolvedFrom(PricingPreviewStepDTO.RESOLVED_FROM_RULE_PERCENT);
        step.setExecuted(true);
        step.setSkipReason(null);
        step.setSkipDetail(null);
        step.setDelta(new BigDecimal("-4000.00"));
        step.setCumulative(new BigDecimal("96000.00"));

        PricingPreviewResponse breakdown = new PricingPreviewResponse();
        breakdown.setContractTypeCode(CODE);
        breakdown.setInvoiceDate(LocalDate.of(2025, 11, 15));
        breakdown.setSumBeforeRules(new BigDecimal("100000.00"));
        breakdown.setSteps(List.of(step));
        breakdown.setTotalBeforeVat(new BigDecimal("96000.00"));
        breakdown.setClampedAtZero(false);
        breakdown.setVatPct(new BigDecimal("25.00"));
        breakdown.setVatAmount(new BigDecimal("24000.00"));
        breakdown.setGrandTotal(new BigDecimal("120000.00"));

        JsonNode root = mapper.readTree(mapper.writeValueAsString(breakdown));

        List<String> topLevel = new ArrayList<>();
        root.fieldNames().forEachRemaining(topLevel::add);
        assertEquals(9, topLevel.size(), "response must serialize exactly 9 top-level fields, got: " + topLevel);
        for (String field : List.of("contractTypeCode", "invoiceDate", "sumBeforeRules", "steps",
                "totalBeforeVat", "clampedAtZero", "vatPct", "vatAmount", "grandTotal")) {
            assertTrue(root.has(field), "missing top-level field: " + field);
        }
        assertEquals("2025-11-15", root.get("invoiceDate").asText(), "invoiceDate must be ISO yyyy-MM-dd");
        assertEquals(false, root.get("clampedAtZero").asBoolean());

        JsonNode stepNode = root.get("steps").get(0);
        List<String> stepFields = new ArrayList<>();
        stepNode.fieldNames().forEachRemaining(stepFields::add);
        assertEquals(13, stepFields.size(), "step must serialize exactly 13 fields, got: " + stepFields);
        for (String field : List.of("ruleId", "label", "type", "purpose", "source", "base",
                "rateOrAmount", "resolvedFrom", "executed", "skipReason", "skipDetail",
                "delta", "cumulative")) {
            assertTrue(stepNode.has(field), "missing step field: " + field);
        }
        assertEquals("PERCENT_DISCOUNT_ON_SUM", stepNode.get("type").asText(), "type must serialize as enum name");
        assertEquals("SUM_BEFORE_DISCOUNTS", stepNode.get("base").asText(), "base must serialize as enum name");
        assertTrue(stepNode.get("skipReason").isNull(), "skipReason must be present-but-null when executed");
        assertTrue(stepNode.get("skipDetail").isNull(), "skipDetail must be present-but-null when executed");
    }

    @Test
    void preview_unknownContractType_propagatesNotFound() {
        ContractTypePricingPreviewResource resource = new ContractTypePricingPreviewResource();
        PricingPreviewService service = mock(PricingPreviewService.class);
        resource.pricingPreviewService = service;

        PricingPreviewRequest request = new PricingPreviewRequest();
        request.setAmount(new BigDecimal("100000.00"));
        when(service.preview("NOPE", request))
                .thenThrow(new NotFoundException("Contract type with code 'NOPE' not found"));

        assertThrows(NotFoundException.class, () -> resource.preview("NOPE", request));
    }
}
