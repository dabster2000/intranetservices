package dk.trustworks.intranet.aggregates.practices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.delivery.dto.cxo.StaffingGapForecastMonthDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticesGrossMarginMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-contract test that locks down the JSON shape produced by Jackson default
 * serialization for every Phase 4 DTO.
 *
 * <p>Each Phase 4 DTO record's JSON representation must:
 * <ul>
 *   <li>Use camelCase keys for every record component (matching the TypeScript
 *       wire interfaces in {@code src/lib/types/cxo.ts}).</li>
 *   <li>Contain NO snake_case keys — Jackson's default policy preserves Java
 *       record component names; a snake_case bleed would indicate that an
 *       {@code @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy)} or
 *       similar config has been mistakenly applied.</li>
 *   <li>Serialize nullable fields as JSON {@code null} (not {@code 0}) so the
 *       frontend's "no data" branch fires correctly.</li>
 * </ul>
 *
 * <p>If this test starts failing, the wire contract has changed and the
 * frontend TS interfaces likely need to be updated in lockstep.</p>
 */
@QuarkusTest
class Phase4DtoJsonShapeTest {

    @Inject
    ObjectMapper mapper;

    // ------------------------------------------------------------------
    // Delivery (CXO scope) DTOs — /delivery/cxo/*
    // ------------------------------------------------------------------

    @Test
    void staffingGapForecastMonthDTO_jsonHasCamelCaseKeys() {
        StaffingGapForecastMonthDTO d = new StaffingGapForecastMonthDTO(
                "202405",
                2024,
                5,
                "May 2024",
                42.5,
                38.0,
                4.5,
                true);
        JsonNode json = mapper.valueToTree(d);

        // Every record component must be present as a camelCase JSON key.
        assertTrue(json.has("monthKey"),    "Expected camelCase 'monthKey'");
        assertTrue(json.has("year"),        "Expected 'year'");
        assertTrue(json.has("monthNumber"), "Expected camelCase 'monthNumber'");
        assertTrue(json.has("monthLabel"),  "Expected camelCase 'monthLabel'");
        assertTrue(json.has("supplyFte"),   "Expected camelCase 'supplyFte'");
        assertTrue(json.has("demandFte"),   "Expected camelCase 'demandFte'");
        assertTrue(json.has("gapFte"),      "Expected camelCase 'gapFte'");
        assertTrue(json.has("isForecast"),  "Expected camelCase 'isForecast'");

        // Snake_case must NOT bleed into the wire format.
        assertFalse(json.has("month_key"),    "Wire format must NOT have snake_case 'month_key'");
        assertFalse(json.has("month_number"), "Wire format must NOT have snake_case 'month_number'");
        assertFalse(json.has("month_label"),  "Wire format must NOT have snake_case 'month_label'");
        assertFalse(json.has("supply_fte"),   "Wire format must NOT have snake_case 'supply_fte'");
        assertFalse(json.has("demand_fte"),   "Wire format must NOT have snake_case 'demand_fte'");
        assertFalse(json.has("gap_fte"),      "Wire format must NOT have snake_case 'gap_fte'");
        assertFalse(json.has("is_forecast"),  "Wire format must NOT have snake_case 'is_forecast'");

        // Sanity-check the values to catch field-name/value mis-mapping.
        assertEquals("202405", json.get("monthKey").asText());
        assertEquals(2024, json.get("year").asInt());
        assertEquals(5, json.get("monthNumber").asInt());
        assertEquals("May 2024", json.get("monthLabel").asText());
        assertEquals(42.5, json.get("supplyFte").asDouble(), 1e-9);
        assertEquals(38.0, json.get("demandFte").asDouble(), 1e-9);
        assertEquals(4.5, json.get("gapFte").asDouble(), 1e-9);
        assertEquals(true, json.get("isForecast").asBoolean());
    }

    // ------------------------------------------------------------------
    // Practices (CXO scope) DTOs — /practices/cxo/*
    // ------------------------------------------------------------------

    @Test
    void practicesGrossMarginMonthDTO_jsonHasCamelCaseKeys() {
        PracticesGrossMarginMonthDTO d = new PracticesGrossMarginMonthDTO(
                "PM",
                10_000_000.0,
                7_500_000.0,
                25.0,
                9_000_000.0,
                7_000_000.0,
                22.222,
                2.778);
        JsonNode json = mapper.valueToTree(d);

        // Every record component must be present as a camelCase JSON key.
        assertTrue(json.has("practiceId"),         "Expected camelCase 'practiceId'");
        assertTrue(json.has("currentRevenueDkk"),  "Expected camelCase 'currentRevenueDkk'");
        assertTrue(json.has("currentCostDkk"),     "Expected camelCase 'currentCostDkk'");
        assertTrue(json.has("currentMarginPct"),   "Expected camelCase 'currentMarginPct'");
        assertTrue(json.has("priorRevenueDkk"),    "Expected camelCase 'priorRevenueDkk'");
        assertTrue(json.has("priorCostDkk"),       "Expected camelCase 'priorCostDkk'");
        assertTrue(json.has("priorMarginPct"),     "Expected camelCase 'priorMarginPct'");
        assertTrue(json.has("marginDeltaPts"),     "Expected camelCase 'marginDeltaPts'");

        // Snake_case must NOT bleed into the wire format.
        assertFalse(json.has("practice_id"),          "Wire format must NOT have snake_case 'practice_id'");
        assertFalse(json.has("current_revenue_dkk"),  "Wire format must NOT have snake_case 'current_revenue_dkk'");
        assertFalse(json.has("current_cost_dkk"),     "Wire format must NOT have snake_case 'current_cost_dkk'");
        assertFalse(json.has("current_margin_pct"),   "Wire format must NOT have snake_case 'current_margin_pct'");
        assertFalse(json.has("prior_revenue_dkk"),    "Wire format must NOT have snake_case 'prior_revenue_dkk'");
        assertFalse(json.has("prior_cost_dkk"),       "Wire format must NOT have snake_case 'prior_cost_dkk'");
        assertFalse(json.has("prior_margin_pct"),     "Wire format must NOT have snake_case 'prior_margin_pct'");
        assertFalse(json.has("margin_delta_pts"),     "Wire format must NOT have snake_case 'margin_delta_pts'");

        // Sanity-check the values to catch field-name/value mis-mapping.
        assertEquals("PM", json.get("practiceId").asText());
        assertEquals(10_000_000.0, json.get("currentRevenueDkk").asDouble(), 1e-9);
        assertEquals(7_500_000.0, json.get("currentCostDkk").asDouble(), 1e-9);
        assertEquals(25.0, json.get("currentMarginPct").asDouble(), 1e-9);
        assertEquals(9_000_000.0, json.get("priorRevenueDkk").asDouble(), 1e-9);
        assertEquals(7_000_000.0, json.get("priorCostDkk").asDouble(), 1e-9);
        assertEquals(22.222, json.get("priorMarginPct").asDouble(), 1e-9);
        assertEquals(2.778, json.get("marginDeltaPts").asDouble(), 1e-9);
    }

    @Test
    void practicesGrossMarginMonthDTO_nullableFieldsSerializeAsJsonNull() {
        // When period revenue is 0, currentMarginPct / priorMarginPct / marginDeltaPts
        // are all null in Java. The wire contract requires JSON null (not omitted, not 0)
        // so the frontend's "no data" branch fires correctly.
        PracticesGrossMarginMonthDTO d = new PracticesGrossMarginMonthDTO(
                "BA",
                0.0,
                0.0,
                null,
                0.0,
                0.0,
                null,
                null);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("currentMarginPct"), "currentMarginPct must be PRESENT (as null), not omitted");
        assertTrue(json.has("priorMarginPct"),   "priorMarginPct must be PRESENT (as null), not omitted");
        assertTrue(json.has("marginDeltaPts"),   "marginDeltaPts must be PRESENT (as null), not omitted");

        assertTrue(json.get("currentMarginPct").isNull(),
                "currentMarginPct must serialize as JSON null when Double is null in Java");
        assertTrue(json.get("priorMarginPct").isNull(),
                "priorMarginPct must serialize as JSON null when Double is null in Java");
        assertTrue(json.get("marginDeltaPts").isNull(),
                "marginDeltaPts must serialize as JSON null when Double is null in Java");
    }
}
