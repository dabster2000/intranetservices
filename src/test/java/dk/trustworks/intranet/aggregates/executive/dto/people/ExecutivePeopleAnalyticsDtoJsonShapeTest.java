package dk.trustworks.intranet.aggregates.executive.dto.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.LeadershipCoverageRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayQuartileRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionCohortPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.StatusTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.UpcomingChangeSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutivePeopleAnalyticsDtoJsonShapeTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void changedPrivacyAndPayContractsRemainCamelCase() {
        assertCamelCase(mapper.valueToTree(new StatusTrendPoint(
                        LocalDate.of(2026, 7, 10), null, null, null, null, null, null,
                        true, "BELOW_PRIVACY_THRESHOLD")),
                "suppressionReason");
        assertCamelCase(mapper.valueToTree(new UpcomingChangeSummary(
                        LocalDate.of(2026, 8, 1), "FIRST_HIRE", null, true,
                        false, "BELOW_PRIVACY_THRESHOLD")),
                "detailAvailable", "detailUnavailableReason");
        assertCamelCase(mapper.valueToTree(new LeadershipCoverageRow(
                        "team", "Team", 8L, null, null, "COVERED", true,
                        true, null, "LEADER_ROLE_HIDDEN_BELOW_PRIVACY_THRESHOLD")),
                "detailAvailable", "detailUnavailableReason", "detailPrivacyReason");
        assertCamelCase(mapper.valueToTree(new RetentionCohortPoint(
                        12, 0, 20L, 3L, 3L, null, 82.5d,
                        false, false, null)),
                "intervalStartMonth", "intervalEvents", "eventsSuppressed", "suppressionReason");
        assertCamelCase(mapper.valueToTree(new PayEquityRow(
                        "OVERALL", "Overall eligible population", -1, "NORMAL",
                        10L, 10L, 60_000d, 57_000d, 62_000d, 55_800d,
                        5d, 10d, true,
                        "OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_AT_LEAST_FIVE_PERCENT", false)),
                "meanPayGapPct", "reviewThresholdMet", "reviewReason");
        assertCamelCase(mapper.valueToTree(new PayQuartileRow(
                        "Q1_LOWEST", "Q1 · Lowest pay", 0, "NORMAL",
                        12L, 8L, 60d, 40d, false)),
                "maleCount", "femaleCount", "maleSharePct", "femaleSharePct");
    }

    private static void assertCamelCase(JsonNode json, String... keys) {
        for (String key : keys) {
            assertTrue(json.has(key), "Expected camelCase key " + key);
            assertFalse(json.has(toSnakeCase(key)), "Wire contract must not expose snake_case key " + key);
        }
    }

    private static String toSnakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
