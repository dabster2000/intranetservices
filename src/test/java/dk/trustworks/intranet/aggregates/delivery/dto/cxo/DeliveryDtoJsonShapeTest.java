package dk.trustworks.intranet.aggregates.delivery.dto.cxo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-contract test that locks down the JSON shape produced by Jackson default
 * serialization for the delivery (CXO scope) DTOs.
 *
 * <p>Each DTO record's JSON representation must:
 * <ul>
 *   <li>Use camelCase keys for every record component (matching the TypeScript
 *       wire interfaces in {@code src/lib/types/cxo.ts}).</li>
 *   <li>Contain NO snake_case keys — Jackson's default policy preserves Java
 *       record component names; a snake_case bleed would indicate that an
 *       {@code @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy)} or
 *       similar config has been mistakenly applied.</li>
 * </ul>
 *
 * <p>If this test starts failing, the wire contract has changed and the
 * frontend TS interfaces likely need to be updated in lockstep.</p>
 */
@QuarkusTest
class DeliveryDtoJsonShapeTest {

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
}
