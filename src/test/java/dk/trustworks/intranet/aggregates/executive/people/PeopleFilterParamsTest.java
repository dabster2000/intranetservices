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
        PeopleFilterParams filters = PeopleFilterParams.from(new PeopleFilterRequest(), CLOCK);

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

        assertEquals(PeopleManagementScope.PEOPLE_LEADERS, PeopleFilterParams.from(leaders, CLOCK).managementScope());
        assertEquals(PeopleManagementScope.SENIOR_LEADERSHIP, PeopleFilterParams.from(senior, CLOCK).managementScope());
    }

    @Test
    void rejectsFutureActualHistoryDate() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-11";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(request, CLOCK));
    }

    @Test
    void rejectsPresentBlankAndInvalidRanges() {
        PeopleFilterRequest blank = new PeopleFilterRequest();
        blank.employeeTypes = " ";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(blank, CLOCK));

        PeopleFilterRequest months = requestWithDate();
        months.months = "18";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(months, CLOCK));

        PeopleFilterRequest horizon = requestWithDate();
        horizon.horizonDays = "365";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(horizon, CLOCK));
    }

    @Test
    void rejectsExternalAndNonCanonicalUuid() {
        PeopleFilterRequest external = requestWithDate();
        external.employeeTypes = "CONSULTANT,EXTERNAL";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(external, CLOCK));

        PeopleFilterRequest uuid = requestWithDate();
        uuid.companyId = "1-1-1-1-1";
        assertThrows(BadRequestException.class, () -> PeopleFilterParams.from(uuid, CLOCK));
    }

    @Test
    void acceptsOnlyClosedEnumsAndCanonicalUuid() {
        PeopleFilterRequest request = requestWithDate();
        request.companyId = "00000000-0000-0000-0000-000000000001";
        request.employeeTypes = "CONSULTANT,STAFF";
        request.population = "ACTIVE";
        request.salaryType = "HOURLY";

        PeopleFilterParams filters = PeopleFilterParams.from(request, CLOCK);
        assertEquals("00000000-0000-0000-0000-000000000001", filters.companyId());
        assertEquals(Set.of(ConsultantType.CONSULTANT, ConsultantType.STAFF), filters.employeeTypes());
        assertEquals(PeoplePopulationScope.ACTIVE, filters.population());
        assertEquals(PeopleSalaryType.HOURLY, filters.salaryType());
    }

    private static PeopleFilterRequest requestWithDate() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-10";
        return request;
    }
}
