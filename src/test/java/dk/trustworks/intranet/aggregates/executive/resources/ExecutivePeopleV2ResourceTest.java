package dk.trustworks.intranet.aggregates.executive.resources;

import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.TestPracticeResolver;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutivePeopleV2ResourceTest {

    @Test
    void exposesExactEighteenEndpointApplicabilityContract() {
        Map<String, Set<String>> expected = Map.ofEntries(
                Map.entry("workforce-summary", set("asOfDate", "companyId", "employeeTypes", "practices")),
                Map.entry("headcount-composition", set("asOfDate", "companyId", "employeeTypes", "population", "practices", "months")),
                Map.entry("status-trend", set("asOfDate", "companyId", "employeeTypes", "population", "practices", "months")),
                Map.entry("gender-trend", set("asOfDate", "companyId", "employeeTypes", "population", "practices", "months")),
                Map.entry("workforce-flow", set("asOfDate", "companyId", "employeeTypes", "practices", "months")),
                Map.entry("upcoming-changes", set("asOfDate", "companyId", "employeeTypes", "practices", "horizonDays")),
                Map.entry("upcoming-changes/detail", set("asOfDate", "companyId", "employeeTypes", "practices", "horizonDays")),
                Map.entry("tenure-distribution", set("asOfDate", "companyId", "employeeTypes", "population", "practices")),
                Map.entry("career-ladder", career()),
                Map.entry("career-mix", career()),
                Map.entry("practice-career-matrix", career()),
                Map.entry("leadership-coverage", set("asOfDate", "companyId", "employeeTypes", "population")),
                Map.entry("leadership-coverage/detail", set("asOfDate", "companyId", "employeeTypes", "population")),
                Map.entry("retention-rate", retention()),
                Map.entry("retention-cohorts", retention()),
                Map.entry("pay-equity", payEquity()),
                Map.entry("pay-quartiles", payQuartiles()),
                Map.entry("pay-trend", payTrend()));

        assertEquals(18, ExecutivePeopleV2Resource.ENDPOINT_QUERY_KEYS.size());
        assertEquals(expected, ExecutivePeopleV2Resource.ENDPOINT_QUERY_KEYS);
    }

    @Test
    void rejectsKnownButInapplicableAndDuplicateParameters() {
        MultivaluedHashMap<String, String> inapplicable = new MultivaluedHashMap<>();
        inapplicable.add("population", "EMPLOYED");
        assertThrows(BadRequestException.class, () -> ExecutivePeopleV2Resource.validateQueryParameters(
                inapplicable, ExecutivePeopleV2Resource.WORKFORCE_SUMMARY_KEYS));

        MultivaluedHashMap<String, String> duplicate = new MultivaluedHashMap<>();
        duplicate.add("months", "12");
        duplicate.add("months", "24");
        assertThrows(BadRequestException.class, () -> ExecutivePeopleV2Resource.validateQueryParameters(
                duplicate, ExecutivePeopleV2Resource.RETENTION_KEYS));

        MultivaluedHashMap<String, String> cohort = new MultivaluedHashMap<>();
        cohort.add("months", "36");
        assertDoesNotThrow(() -> ExecutivePeopleV2Resource.validateQueryParameters(
                cohort, ExecutivePeopleV2Resource.RETENTION_KEYS));

        MultivaluedHashMap<String, String> quartiles = new MultivaluedHashMap<>();
        quartiles.add("compensationGroup", "CAREER_BAND");
        assertThrows(BadRequestException.class, () -> ExecutivePeopleV2Resource.validateQueryParameters(
                quartiles, ExecutivePeopleV2Resource.PAY_QUARTILE_KEYS));
    }

    @Test
    void enforcesLockedCareerAndLeadershipPopulations() {
        PeopleFilterRequest omittedCareerType = new PeopleFilterRequest();
        ExecutivePeopleV2Resource.applyCareerDefaults(omittedCareerType);
        assertEquals("CONSULTANT", omittedCareerType.employeeTypes);
        assertDoesNotThrow(() -> ExecutivePeopleV2Resource.validateCareerInvariants(
                PeopleFilterParams.from(omittedCareerType, TestPracticeResolver.RESOLVER)));

        PeopleFilterRequest career = new PeopleFilterRequest();
        career.employeeTypes = "CONSULTANT";
        career.population = "EMPLOYED";
        assertDoesNotThrow(() -> ExecutivePeopleV2Resource.validateCareerInvariants(PeopleFilterParams.from(career, TestPracticeResolver.RESOLVER)));
        career.employeeTypes = "STAFF";
        assertThrows(BadRequestException.class,
                () -> ExecutivePeopleV2Resource.validateCareerInvariants(PeopleFilterParams.from(career, TestPracticeResolver.RESOLVER)));

        PeopleFilterRequest leadership = new PeopleFilterRequest();
        leadership.employeeTypes = "CONSULTANT,STAFF,STUDENT";
        leadership.population = "EMPLOYED";
        assertDoesNotThrow(() -> ExecutivePeopleV2Resource.validateLeadershipInvariants(
                PeopleFilterParams.from(leadership, TestPracticeResolver.RESOLVER)));
        leadership.population = "ACTIVE";
        assertThrows(BadRequestException.class,
                () -> ExecutivePeopleV2Resource.validateLeadershipInvariants(PeopleFilterParams.from(leadership, TestPracticeResolver.RESOLVER)));
    }

    private static Set<String> career() {
        return set("asOfDate", "companyId", "employeeTypes", "population", "practices",
                "careerTracks", "careerLevels", "managementScope");
    }

    private static Set<String> retention() {
        return set("asOfDate", "companyId", "employeeTypes", "population", "practices",
                "careerTracks", "careerLevels", "managementScope", "months");
    }

    private static Set<String> payEquity() {
        return set("asOfDate", "companyId", "employeeTypes", "population", "practices",
                "careerTracks", "careerLevels", "managementScope", "compensationGroup", "salaryType");
    }

    private static Set<String> payTrend() {
        return set("asOfDate", "companyId", "employeeTypes", "population", "practices",
                "careerTracks", "careerLevels", "managementScope", "months", "salaryType");
    }

    private static Set<String> payQuartiles() {
        return set("asOfDate", "companyId", "employeeTypes", "population", "practices",
                "careerTracks", "careerLevels", "managementScope", "salaryType");
    }

    private static Set<String> set(String... values) {
        return Set.of(values);
    }
}
