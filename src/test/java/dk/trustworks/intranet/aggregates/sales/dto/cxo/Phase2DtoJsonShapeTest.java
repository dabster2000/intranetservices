package dk.trustworks.intranet.aggregates.sales.dto.cxo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
