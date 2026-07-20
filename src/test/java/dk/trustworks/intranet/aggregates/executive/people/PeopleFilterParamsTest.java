package dk.trustworks.intranet.aggregates.executive.people;

import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeopleFilterParamsTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T10:00:00Z"), ZoneId.of("Europe/Copenhagen"));

    @Test
    void defaultsAreDeterministicAndInternal() {
        PeopleFilterParams filters = PeopleFilterParams.from(new PeopleFilterRequest(), CLOCK, TestPracticeResolver.RESOLVER);

        assertEquals("2026-07-10", filters.asOfDate().toString());
        assertEquals(24, filters.months());
        assertEquals(90, filters.horizonDays());
        assertEquals(PeoplePopulationScope.EMPLOYED, filters.population());
        assertEquals(Set.of(ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT),
                filters.employeeTypes());
        assertEquals(PeopleManagementScope.ALL, filters.managementScope());
        assertEquals(PeopleCompensationGroup.CAREER_BAND, filters.compensationGroup());
        assertEquals(PeopleSalaryType.NORMAL, filters.salaryType());
    }

    @Test
    void parsesTheTwoManagementScopesAsDifferentValues() {
        PeopleFilterRequest leaders = requestWithDate();
        leaders.managementScope = "PEOPLE_LEADERS";
        PeopleFilterRequest senior = requestWithDate();
        senior.managementScope = "SENIOR_LEADERSHIP";

        assertEquals(PeopleManagementScope.PEOPLE_LEADERS, PeopleFilterParams.from(leaders, CLOCK, TestPracticeResolver.RESOLVER).managementScope());
        assertEquals(PeopleManagementScope.SENIOR_LEADERSHIP, PeopleFilterParams.from(senior, CLOCK, TestPracticeResolver.RESOLVER).managementScope());
    }

    @Test
    void rejectsFutureActualHistoryDate() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-11";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(request, CLOCK, TestPracticeResolver.RESOLVER));
    }

    @Test
    void rejectsPresentBlankAndInvalidRanges() {
        PeopleFilterRequest blank = new PeopleFilterRequest();
        blank.employeeTypes = " ";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(blank, CLOCK, TestPracticeResolver.RESOLVER));

        PeopleFilterRequest months = requestWithDate();
        months.months = "18";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(months, CLOCK, TestPracticeResolver.RESOLVER));

        PeopleFilterRequest horizon = requestWithDate();
        horizon.horizonDays = "365";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(horizon, CLOCK, TestPracticeResolver.RESOLVER));
    }

    @Test
    void rejectsExternalAndNonCanonicalUuid() {
        PeopleFilterRequest external = requestWithDate();
        external.employeeTypes = "CONSULTANT,EXTERNAL";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(external, CLOCK, TestPracticeResolver.RESOLVER));

        PeopleFilterRequest uuid = requestWithDate();
        uuid.companyId = "1-1-1-1-1";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(uuid, CLOCK, TestPracticeResolver.RESOLVER));
    }

    @Test
    void acceptsOnlyClosedEnumsAndCanonicalUuid() {
        PeopleFilterRequest request = requestWithDate();
        request.companyId = "00000000-0000-0000-0000-000000000001";
        request.employeeTypes = "CONSULTANT,STAFF";
        request.population = "ACTIVE";
        request.salaryType = "HOURLY";
        request.compensationGroup = "DISCO_FUNCTION";

        PeopleFilterParams filters = PeopleFilterParams.from(request, CLOCK, TestPracticeResolver.RESOLVER);
        assertEquals("00000000-0000-0000-0000-000000000001", filters.companyId());
        assertEquals(Set.of(ConsultantType.CONSULTANT, ConsultantType.STAFF), filters.employeeTypes());
        assertEquals(PeoplePopulationScope.ACTIVE, filters.population());
        assertEquals(PeopleSalaryType.HOURLY, filters.salaryType());
        assertEquals(PeopleCompensationGroup.DISCO_FUNCTION, filters.compensationGroup());
    }

    // ── practices: registry-validated codes or uuids (Phase 3, §4.5) ──────

    @Test
    void practicesAcceptStorageCodesCaseInsensitively() {
        PeopleFilterRequest request = requestWithDate();
        request.practices = "pm,SA";
        PeopleFilterParams filters = PeopleFilterParams.from(request, CLOCK, TestPracticeResolver.RESOLVER);
        assertEquals(Set.of("PM", "SA"), filters.practices());
    }

    @Test
    void practicesAcceptRegistryUuidsAndNormalizeToStorageCodes() {
        PeopleFilterRequest request = requestWithDate();
        request.practices = TestPracticeResolver.REGISTRY_UUIDS.get("BA") + ",CYB";
        PeopleFilterParams filters = PeopleFilterParams.from(request, CLOCK, TestPracticeResolver.RESOLVER);
        assertEquals(Set.of("BA", "CYB"), filters.practices());
    }

    @Test
    void practicesAcceptTheUdSentinelRegistryRow() {
        PeopleFilterRequest request = requestWithDate();
        request.practices = "UD";
        assertEquals(Set.of("UD"),
                PeopleFilterParams.from(request, CLOCK, TestPracticeResolver.RESOLVER).practices());
    }

    @Test
    void practicesRejectRetiredAndUnknownValues() {
        // JK has no registry row since V419 — the registry-backed resolver is
        // exactly what keeps retired codes out (formerly a valid enum constant).
        PeopleFilterRequest jk = requestWithDate();
        jk.practices = "JK";
        assertThrows(BadRequestException.class,
                () -> PeopleFilterParams.from(jk, CLOCK, TestPracticeResolver.RESOLVER));

        PeopleFilterRequest garbage = requestWithDate();
        garbage.practices = "NOPE";
        assertThrows(BadRequestException.class,
                () -> PeopleFilterParams.from(garbage, CLOCK, TestPracticeResolver.RESOLVER));

        PeopleFilterRequest blankItem = requestWithDate();
        blankItem.practices = "PM,,SA";
        assertThrows(BadRequestException.class,
                () -> PeopleFilterParams.from(blankItem, CLOCK, TestPracticeResolver.RESOLVER));
    }

    @Test
    void absentPracticesMeansNoFilter() {
        assertEquals(Set.of(),
                PeopleFilterParams.from(requestWithDate(), CLOCK, TestPracticeResolver.RESOLVER).practices());
    }

    private static PeopleFilterRequest requestWithDate() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-10";
        return request;
    }
}
