package dk.trustworks.intranet.aggregates.sales.dto.cxo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.CapacityDemandMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.CapacityDemandPracticeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffPracticeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthStageDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.RevenueForecastBandMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateDealTypeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRatePracticeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateStageDTO;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Phase 2 DTO records serialize to the exact JSON keys the frontend
 * TypeScript types in src/lib/types/cxo.ts already consume. A record
 * component rename, accidental @JsonProperty addition, or boolean naming-
 * convention drift would silently break the dashboard; this test fails fast.
 *
 * Plain Jackson serialization (no Quarkus boot) — runs on every developer's
 * machine without DB or config dependencies.
 */
class Phase2DtoJsonShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Set<String> keysOf(Object dto) {
        JsonNode node = mapper.valueToTree(dto);
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ============================================================================
    // Sales DTOs
    // ============================================================================

    @Test
    void pipelineFunnelStageDto_jsonKeys() {
        var dto = new PipelineFunnelStageDTO("DETECTED", "Detected", 100.0, 50.0, 3L);
        assertEquals(
                Set.of("stageId", "stageLabel", "expectedRevenueDkk", "weightedPipelineDkk", "opportunityCount"),
                keysOf(dto));
    }

    @Test
    void backlogCoverageMonthDto_jsonKeys() {
        var dto = new BacklogCoverageMonthDTO("202501", 2025, 1, "Jan 2025", 1000.0, 5.0);
        assertEquals(
                Set.of("monthKey", "year", "monthNumber", "monthLabel", "backlogRevenueDkk", "consultantCount"),
                keysOf(dto));
    }

    @Test
    void pipelineTrendMonthDto_jsonKeys() {
        var dto = new PipelineTrendMonthDTO("202501", 2025, 1, "Jan 2025", 1000.0);
        assertEquals(
                Set.of("monthKey", "year", "monthNumber", "monthLabel", "weightedPipelineDkk"),
                keysOf(dto));
    }

    // ============================================================================
    // Forecast DTOs
    // ============================================================================

    @Test
    void contractRunoffPracticeDto_jsonKeys_includesIsExpiringPrefix() {
        var dto = new ContractRunoffPracticeDTO("PM", 100.0, true);
        // CRITICAL: TS contract is `isExpiring: boolean`, so JSON key MUST be "isExpiring"
        // (not "expiring"). Java record accessors don't follow JavaBean is-prefix stripping
        // — Jackson uses the record component name verbatim.
        assertEquals(Set.of("practice", "revenueDkk", "isExpiring"), keysOf(dto));
    }

    @Test
    void contractRunoffMonthDto_jsonKeys() {
        var dto = new ContractRunoffMonthDTO(
                "202501", "Jan 2025", 100.0, 50.0, 2L,
                List.of(new ContractRunoffPracticeDTO("PM", 100.0, false)),
                100.0, 0.0);
        assertEquals(
                Set.of("month", "monthLabel", "activeRevenueDkk", "expiringRevenueDkk",
                        "expiringContractCount", "byPractice", "newRevenueDkk", "extensionRevenueDkk"),
                keysOf(dto));
    }

    @Test
    void winRatePracticeDto_jsonKeys() {
        var dto = new WinRatePracticeDTO("PM", 50.0, 10L);
        assertEquals(Set.of("practice", "calibratedPct", "sampleSize"), keysOf(dto));
    }

    @Test
    void winRateDealTypeDto_jsonKeys() {
        var dto = new WinRateDealTypeDTO("NEW", 50.0, 10L);
        assertEquals(Set.of("dealType", "calibratedPct", "sampleSize"), keysOf(dto));
    }

    @Test
    void winRateStageDto_jsonKeys() {
        var dto = new WinRateStageDTO(
                "DETECTED", "Detected", 30.0, 25.0, 5.0, 100L, 30L, 100L,
                List.of(), List.of());
        assertEquals(
                Set.of("stageId", "stageLabel", "calibratedWinRatePct", "staticProbabilityPct",
                        "deltaPct", "sampleSize", "wonCount", "reachedCount",
                        "byPractice", "byDealType"),
                keysOf(dto));
    }

    @Test
    void pipelineHealthStageDto_jsonKeys() {
        var dto = new PipelineHealthStageDTO("DETECTED", 100.0, 50.0, 3L);
        assertEquals(
                Set.of("stageId", "expectedDkk", "weightedDkk", "opportunityCount"),
                keysOf(dto));
    }

    @Test
    void pipelineHealthMonthDto_jsonKeys() {
        var dto = new PipelineHealthMonthDTO(
                "202501", "Jan 2025", 1000.0, 500.0, 750.0, 0.67,
                List.of(new PipelineHealthStageDTO("DETECTED", 100.0, 50.0, 3L)));
        assertEquals(
                Set.of("month", "monthLabel", "totalExpectedDkk", "totalWeightedDkk",
                        "budgetTargetDkk", "coverageRatio", "byStage"),
                keysOf(dto));
    }

    @Test
    void revenueForecastBandMonthDto_jsonKeys_actualRevenueIsNullable() {
        // Past/current month: actualRevenueDkk has a value.
        var withActual = new RevenueForecastBandMonthDTO(
                "202501", "Jan 2025", 1000.0, 1100.0, 800.0, 1000.0, 1200.0);
        assertEquals(
                Set.of("month", "monthLabel", "actualRevenueDkk", "budgetDkk",
                        "forecastLowDkk", "forecastMidDkk", "forecastHighDkk"),
                keysOf(withActual));

        // Future month: actualRevenueDkk is null. Jackson must serialize null
        // (not omit the field) so the frontend's `number | null` contract is honored.
        var futureMonth = new RevenueForecastBandMonthDTO(
                "202612", "Dec 2026", null, 1100.0, 800.0, 1000.0, 1200.0);
        Set<String> futureKeys = keysOf(futureMonth);
        // Confirm the key is still present (Jackson default includes nulls)
        // — if a future @JsonInclude config strips nulls, this test catches it.
        assertEquals(
                Set.of("month", "monthLabel", "actualRevenueDkk", "budgetDkk",
                        "forecastLowDkk", "forecastMidDkk", "forecastHighDkk"),
                futureKeys,
                "actualRevenueDkk must be serialized as JSON null, not omitted");

        // Lock down that the value is JSON null specifically (not a numeric coercion to 0)
        // — if a Jackson config or @JsonProperty annotation ever silently coerced the
        // null Double to 0.0, the previous key-set assertion would still pass.
        JsonNode futureNode = mapper.valueToTree(futureMonth);
        assertTrue(futureNode.get("actualRevenueDkk").isNull(),
                "actualRevenueDkk must serialize as JSON null (not 0) for future months");
    }

    @Test
    void capacityDemandPracticeDto_jsonKeys() {
        var dto = new CapacityDemandPracticeDTO("PM", 5.0, 4.0, 1.0);
        assertEquals(Set.of("practice", "capacityFte", "demandFte", "gapFte"), keysOf(dto));
    }

    @Test
    void capacityDemandMonthDto_jsonKeys() {
        var dto = new CapacityDemandMonthDTO(
                "202501", "Jan 2025", 10.0, 4.0, 2.0, 6.0, 4.0,
                List.of(new CapacityDemandPracticeDTO("PM", 5.0, 4.0, 1.0)));
        assertEquals(
                Set.of("month", "monthLabel", "capacityFte", "backlogDemandFte",
                        "pipelineDemandFte", "totalDemandFte", "gapFte", "byPractice"),
                keysOf(dto));
    }
}
