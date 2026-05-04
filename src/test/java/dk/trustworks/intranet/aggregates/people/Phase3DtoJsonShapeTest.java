package dk.trustworks.intranet.aggregates.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecAgeBucketDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecCareerLevelDistDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecGenderTrendMonthDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecHeadcountByTypeMonthDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortPointDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.ConsultantPyramidDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.HeadcountGrowthMonthDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.PyramidLevelDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.TurnoverTtmMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-contract test that locks down the JSON shape produced by Jackson default
 * serialization for every Phase 3 DTO.
 *
 * <p>Each Phase 3 DTO record's JSON representation must:
 * <ul>
 *   <li>Use camelCase keys for every record component (matching the TypeScript
 *       wire interfaces in {@code src/lib/types/cxo.ts} and
 *       {@code src/lib/types/executive.ts}).</li>
 *   <li>Contain NO snake_case keys — Jackson's default policy preserves Java
 *       record component names; a snake_case bleed would indicate that an
 *       {@code @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy)} or
 *       similar config has been mistakenly applied.</li>
 *   <li>Serialize nested record types (PyramidLevelDTO inside
 *       ConsultantPyramidDTO, ExecRetentionCohortPointDTO inside
 *       ExecRetentionCohortDTO) with the same camelCase convention.</li>
 * </ul>
 *
 * <p>If this test starts failing, the wire contract has changed and the
 * frontend TS interfaces likely need to be updated in lockstep.</p>
 */
@QuarkusTest
class Phase3DtoJsonShapeTest {

    @Inject
    ObjectMapper mapper;

    // ------------------------------------------------------------------
    // People (CXO scope) DTOs — /people/cxo/*
    // ------------------------------------------------------------------

    @Test
    void turnoverTtmMonthDTO_jsonHasCamelCaseKeys() {
        TurnoverTtmMonthDTO d = new TurnoverTtmMonthDTO(
                "202405",
                "May 2024",
                2024,
                5,
                3L,
                1L,
                2L);
        JsonNode json = mapper.valueToTree(d);

        // Every record component must be present as a camelCase JSON key.
        assertTrue(json.has("monthKey"),    "Expected camelCase 'monthKey'");
        assertTrue(json.has("monthLabel"),  "Expected camelCase 'monthLabel'");
        assertTrue(json.has("year"),        "Expected 'year'");
        assertTrue(json.has("monthNumber"), "Expected camelCase 'monthNumber'");
        assertTrue(json.has("hires"),       "Expected 'hires'");
        assertTrue(json.has("terminations"),"Expected 'terminations'");
        assertTrue(json.has("net"),         "Expected 'net'");

        // Snake_case must NOT bleed into the wire format.
        assertFalse(json.has("month_key"),    "Wire format must NOT have snake_case 'month_key'");
        assertFalse(json.has("month_label"),  "Wire format must NOT have snake_case 'month_label'");
        assertFalse(json.has("month_number"), "Wire format must NOT have snake_case 'month_number'");

        // Sanity-check the values to catch field-name/value mis-mapping.
        assertEquals("202405", json.get("monthKey").asText());
        assertEquals("May 2024", json.get("monthLabel").asText());
        assertEquals(2024, json.get("year").asInt());
        assertEquals(5, json.get("monthNumber").asInt());
        assertEquals(3L, json.get("hires").asLong());
        assertEquals(1L, json.get("terminations").asLong());
        assertEquals(2L, json.get("net").asLong());
    }

    @Test
    void pyramidLevelDTO_jsonHasCamelCaseKeys() {
        PyramidLevelDTO d = new PyramidLevelDTO(
                "Junior",
                List.of("JUNIOR_CONSULTANT", "PROFESSIONAL_CONSULTANT"),
                12L,
                30.5,
                25.0);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("bucketLabel"),   "Expected camelCase 'bucketLabel'");
        assertTrue(json.has("careerLevels"),  "Expected camelCase 'careerLevels'");
        assertTrue(json.has("actualCount"),   "Expected camelCase 'actualCount'");
        assertTrue(json.has("actualPercent"), "Expected camelCase 'actualPercent'");
        assertTrue(json.has("targetPercent"), "Expected camelCase 'targetPercent'");

        assertFalse(json.has("bucket_label"),   "Wire format must NOT have snake_case 'bucket_label'");
        assertFalse(json.has("career_levels"),  "Wire format must NOT have snake_case 'career_levels'");
        assertFalse(json.has("actual_count"),   "Wire format must NOT have snake_case 'actual_count'");
        assertFalse(json.has("actual_percent"), "Wire format must NOT have snake_case 'actual_percent'");
        assertFalse(json.has("target_percent"), "Wire format must NOT have snake_case 'target_percent'");

        // careerLevels must be a JSON array, not a stringified JSON value.
        assertTrue(json.get("careerLevels").isArray(), "careerLevels must be a JSON array");
        assertEquals(2, json.get("careerLevels").size());
    }

    @Test
    void consultantPyramidDTO_jsonHasCamelCaseKeysAndNestedLevelsAreCamelCase() {
        PyramidLevelDTO level = new PyramidLevelDTO(
                "Senior",
                List.of("SENIOR_CONSULTANT"),
                7L,
                17.5,
                20.0);
        ConsultantPyramidDTO d = new ConsultantPyramidDTO(
                List.of(level),
                40L,
                "2026-05-03");
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("levels"),           "Expected 'levels'");
        assertTrue(json.has("totalConsultants"), "Expected camelCase 'totalConsultants'");
        assertTrue(json.has("snapshotDate"),     "Expected camelCase 'snapshotDate'");

        assertFalse(json.has("total_consultants"), "Wire format must NOT have snake_case 'total_consultants'");
        assertFalse(json.has("snapshot_date"),     "Wire format must NOT have snake_case 'snapshot_date'");

        // Nested array — verify the first PyramidLevelDTO element keeps camelCase keys.
        JsonNode firstLevel = json.get("levels").get(0);
        assertNotNull(firstLevel, "Expected levels[0] to be present");
        assertTrue(firstLevel.has("bucketLabel"),   "Nested levels[0].bucketLabel must be camelCase");
        assertTrue(firstLevel.has("careerLevels"),  "Nested levels[0].careerLevels must be camelCase");
        assertTrue(firstLevel.has("actualCount"),   "Nested levels[0].actualCount must be camelCase");
        assertTrue(firstLevel.has("actualPercent"), "Nested levels[0].actualPercent must be camelCase");
        assertTrue(firstLevel.has("targetPercent"), "Nested levels[0].targetPercent must be camelCase");
        assertFalse(firstLevel.has("bucket_label"), "Nested levels[0] must NOT have snake_case keys");
    }

    @Test
    void headcountGrowthMonthDTO_jsonHasCamelCaseKeys() {
        HeadcountGrowthMonthDTO d = new HeadcountGrowthMonthDTO(
                "202405",
                "May 2024",
                2024,
                5,
                30L,
                4L,
                6L,
                40L);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("monthKey"),    "Expected camelCase 'monthKey'");
        assertTrue(json.has("monthLabel"),  "Expected camelCase 'monthLabel'");
        assertTrue(json.has("year"),        "Expected 'year'");
        assertTrue(json.has("monthNumber"), "Expected camelCase 'monthNumber'");
        assertTrue(json.has("consultant"),  "Expected 'consultant'");
        assertTrue(json.has("student"),     "Expected 'student'");
        assertTrue(json.has("staff"),       "Expected 'staff'");
        assertTrue(json.has("total"),       "Expected 'total'");

        assertFalse(json.has("month_key"),    "Wire format must NOT have snake_case 'month_key'");
        assertFalse(json.has("month_label"),  "Wire format must NOT have snake_case 'month_label'");
        assertFalse(json.has("month_number"), "Wire format must NOT have snake_case 'month_number'");
    }

    // ------------------------------------------------------------------
    // Executive (people) DTOs — /executive/people/*
    // ------------------------------------------------------------------

    @Test
    void execAgeBucketDTO_jsonHasCamelCaseKeys() {
        ExecAgeBucketDTO d = new ExecAgeBucketDTO(
                "25–29",
                25,
                3L,
                2L,
                1L,
                6L);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("bucket"),       "Expected 'bucket'");
        assertTrue(json.has("bucketStart"),  "Expected camelCase 'bucketStart'");
        assertTrue(json.has("maleCount"),    "Expected camelCase 'maleCount'");
        assertTrue(json.has("femaleCount"),  "Expected camelCase 'femaleCount'");
        assertTrue(json.has("unknownCount"), "Expected camelCase 'unknownCount'");
        assertTrue(json.has("total"),        "Expected 'total'");

        assertFalse(json.has("bucket_start"),  "Wire format must NOT have snake_case 'bucket_start'");
        assertFalse(json.has("male_count"),    "Wire format must NOT have snake_case 'male_count'");
        assertFalse(json.has("female_count"),  "Wire format must NOT have snake_case 'female_count'");
        assertFalse(json.has("unknown_count"), "Wire format must NOT have snake_case 'unknown_count'");
    }

    @Test
    void execGenderTrendMonthDTO_jsonHasCamelCaseKeys() {
        ExecGenderTrendMonthDTO d = new ExecGenderTrendMonthDTO(
                "202405",
                "May 2024",
                2024,
                5,
                10L,
                4L,
                1L,
                28.57);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("monthKey"),     "Expected camelCase 'monthKey'");
        assertTrue(json.has("monthLabel"),   "Expected camelCase 'monthLabel'");
        assertTrue(json.has("year"),         "Expected 'year'");
        assertTrue(json.has("monthNumber"),  "Expected camelCase 'monthNumber'");
        assertTrue(json.has("maleCount"),    "Expected camelCase 'maleCount'");
        assertTrue(json.has("femaleCount"),  "Expected camelCase 'femaleCount'");
        assertTrue(json.has("unknownCount"), "Expected camelCase 'unknownCount'");
        assertTrue(json.has("femalePct"),    "Expected camelCase 'femalePct'");

        assertFalse(json.has("month_key"),     "Wire format must NOT have snake_case 'month_key'");
        assertFalse(json.has("month_label"),   "Wire format must NOT have snake_case 'month_label'");
        assertFalse(json.has("month_number"),  "Wire format must NOT have snake_case 'month_number'");
        assertFalse(json.has("male_count"),    "Wire format must NOT have snake_case 'male_count'");
        assertFalse(json.has("female_count"),  "Wire format must NOT have snake_case 'female_count'");
        assertFalse(json.has("unknown_count"), "Wire format must NOT have snake_case 'unknown_count'");
        assertFalse(json.has("female_pct"),    "Wire format must NOT have snake_case 'female_pct'");
    }

    @Test
    void execHeadcountByTypeMonthDTO_jsonHasCamelCaseKeys() {
        ExecHeadcountByTypeMonthDTO d = new ExecHeadcountByTypeMonthDTO(
                "202405",
                "May 2024",
                2024,
                5,
                30L,
                4L,
                6L,
                2L,
                42L);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("monthKey"),    "Expected camelCase 'monthKey'");
        assertTrue(json.has("monthLabel"),  "Expected camelCase 'monthLabel'");
        assertTrue(json.has("year"),        "Expected 'year'");
        assertTrue(json.has("monthNumber"), "Expected camelCase 'monthNumber'");
        assertTrue(json.has("consultant"),  "Expected 'consultant'");
        assertTrue(json.has("student"),     "Expected 'student'");
        assertTrue(json.has("staff"),       "Expected 'staff'");
        assertTrue(json.has("external"),    "Expected 'external'");
        assertTrue(json.has("total"),       "Expected 'total'");

        assertFalse(json.has("month_key"),    "Wire format must NOT have snake_case 'month_key'");
        assertFalse(json.has("month_label"),  "Wire format must NOT have snake_case 'month_label'");
        assertFalse(json.has("month_number"), "Wire format must NOT have snake_case 'month_number'");
    }

    @Test
    void execRetentionCohortPointDTO_jsonHasCamelCaseKeys() {
        ExecRetentionCohortPointDTO d = new ExecRetentionCohortPointDTO(
                12,
                85.5);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("monthsSinceHire"), "Expected camelCase 'monthsSinceHire'");
        assertTrue(json.has("survivalPct"),     "Expected camelCase 'survivalPct'");

        assertFalse(json.has("months_since_hire"), "Wire format must NOT have snake_case 'months_since_hire'");
        assertFalse(json.has("survival_pct"),      "Wire format must NOT have snake_case 'survival_pct'");
    }

    @Test
    void execRetentionCohortDTO_jsonHasCamelCaseKeysAndNestedPointsAreCamelCase() {
        ExecRetentionCohortPointDTO point = new ExecRetentionCohortPointDTO(0, 100.0);
        ExecRetentionCohortDTO d = new ExecRetentionCohortDTO(
                2024,
                15L,
                List.of(point));
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("cohortYear"), "Expected camelCase 'cohortYear'");
        assertTrue(json.has("cohortSize"), "Expected camelCase 'cohortSize'");
        assertTrue(json.has("points"),     "Expected 'points'");

        assertFalse(json.has("cohort_year"), "Wire format must NOT have snake_case 'cohort_year'");
        assertFalse(json.has("cohort_size"), "Wire format must NOT have snake_case 'cohort_size'");

        // Nested array — verify the first ExecRetentionCohortPointDTO element keeps camelCase keys.
        JsonNode firstPoint = json.get("points").get(0);
        assertNotNull(firstPoint, "Expected points[0] to be present");
        assertTrue(firstPoint.has("monthsSinceHire"), "Nested points[0].monthsSinceHire must be camelCase");
        assertTrue(firstPoint.has("survivalPct"),     "Nested points[0].survivalPct must be camelCase");
        assertFalse(firstPoint.has("months_since_hire"), "Nested points[0] must NOT have snake_case keys");
        assertFalse(firstPoint.has("survival_pct"),      "Nested points[0] must NOT have snake_case keys");
    }

    @Test
    void execCareerLevelDistDTO_jsonHasCamelCaseKeys() {
        ExecCareerLevelDistDTO d = new ExecCareerLevelDistDTO(
                "SENIOR_CONSULTANT",
                "DELIVERY",
                7L);
        JsonNode json = mapper.valueToTree(d);

        assertTrue(json.has("careerLevel"), "Expected camelCase 'careerLevel'");
        assertTrue(json.has("careerTrack"), "Expected camelCase 'careerTrack'");
        assertTrue(json.has("count"),       "Expected 'count'");

        assertFalse(json.has("career_level"), "Wire format must NOT have snake_case 'career_level'");
        assertFalse(json.has("career_track"), "Wire format must NOT have snake_case 'career_track'");
    }
}
