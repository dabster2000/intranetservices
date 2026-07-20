package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the new CXO DTO records serialize to the exact JSON keys the
 * frontend TypeScript types in src/lib/types/cxo.ts already consume. A record
 * component rename or @JsonProperty addition would silently break the frontend;
 * this test fails fast.
 *
 * Plain Jackson serialization (no Quarkus) — runs without DB.
 */
class CxoDtoJsonShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Set<String> keysOf(Object dto) throws Exception {
        JsonNode node = mapper.valueToTree(dto);
        Set<String> keys = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void costToRevenueDataPointDto_jsonKeys() throws Exception {
        var dto = new CostToRevenueDataPointDTO("202501", 2025, 1, "Jan 2025", 100.0, 30.0, 20.0, 50.0);
        assertEquals(
            Set.of("monthKey", "year", "monthNumber", "monthLabel",
                   "revenueDkk", "deliveryCostDkk", "opexDkk", "costToRevenueRatioPct"),
            keysOf(dto));
    }

    @Test
    void grossMarginTrendDataPointDto_jsonKeys() throws Exception {
        var dto = new GrossMarginTrendDataPointDTO("202501", 2025, 1, "Jan 2025", 100.0, 30.0, 70.0);
        assertEquals(
            Set.of("monthKey", "year", "monthNumber", "monthLabel",
                   "totalRevenueDkk", "totalCostDkk", "grossMarginPct"),
            keysOf(dto));
    }

    @Test
    void monthlyRevenuePracticeDataPoint_jsonKeys() throws Exception {
        var dto = new MonthlyRevenuePracticeDataPoint("202501", 2025, 1, "Jan 2025",
                Map.of("PM", 100.0), 100.0, 30.0, 70.0);
        assertEquals(
            Set.of("monthKey", "year", "monthNumber", "monthLabel",
                   "practiceRevenue", "totalRevenueDkk", "totalCostDkk", "marginPercent"),
            keysOf(dto));
    }

    @Test
    void revenuePracticeDto_jsonKeys() throws Exception {
        var dto = new RevenuePracticeDTO(List.of(), List.of("PM"), List.of(new RevenuePracticeDTO.PracticeDetail("uuid-pm", "PM", "PM", "Project Managers")));
        assertEquals(Set.of("months", "practices", "practiceDetails"), keysOf(dto));
    }

    @Test
    void quarterlyNewVsRepeatDto_jsonKeys() throws Exception {
        var dto = new QuarterlyNewVsRepeatDTO(2025, 1, "Q1 2025", 100.0, 50.0, 150.0, 33.33);
        assertEquals(
            Set.of("year", "quarter", "quarterLabel",
                   "newRevenueDkk", "repeatRevenueDkk", "totalRevenueDkk", "repeatSharePercent"),
            keysOf(dto));
    }

    @Test
    void newVsRepeatClientRevenueDto_jsonKeys() throws Exception {
        var dto = new NewVsRepeatClientRevenueDTO(List.of());
        assertEquals(Set.of("quarters"), keysOf(dto));
    }

    @Test
    void monthlyCreditNoteDto_jsonKeys() throws Exception {
        var dto = new MonthlyCreditNoteDTO("202501", 2025, 1, "Jan 2025", 100.0, 5.0, 5.0);
        assertEquals(
            Set.of("monthKey", "year", "monthNumber", "monthLabel",
                   "invoiceAmountDkk", "creditNoteAmountDkk", "creditNoteRatePct"),
            keysOf(dto));
    }

    @Test
    void creditNoteTopClientDto_jsonKeys() throws Exception {
        var dto = new CreditNoteTopClientDTO("Acme A/S", 5000.0, 3L);
        assertEquals(Set.of("clientName", "creditNoteAmountDkk", "creditNoteCount"), keysOf(dto));
    }

    @Test
    void creditNoteRateDto_jsonKeys() throws Exception {
        var dto = new CreditNoteRateDTO(List.of(), List.of());
        assertEquals(Set.of("monthly", "topClients"), keysOf(dto));
    }
}
