package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecHeadcountByTypeMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link ExecutivePeopleService#headcountByType(Set)}
 * mirroring the BFF route at {@code /api/executive/headcount-by-type}.
 *
 * <p>Trailing 24 months on {@code userstatus} for active employees with
 * {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF', 'EXTERNAL')}. Differs from
 * the CXO {@code headcount-growth} curve by including EXTERNAL.</p>
 */
@QuarkusTest
class ExecutivePeopleServiceHeadcountByTypeTest {

    @Inject
    ExecutivePeopleService service;

    @Test
    void headcountByType_noCompanyFilter_returnsList() {
        List<ExecHeadcountByTypeMonthDTO> result = service.headcountByType(null);
        assertNotNull(result);
        String prevKey = "";
        for (ExecHeadcountByTypeMonthDTO row : result) {
            assertNotNull(row.monthKey(), "monthKey must not be null");
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.monthKey().compareTo(prevKey) >= 0,
                    "monthKey must be ascending: prev=" + prevKey + " current=" + row.monthKey());
            prevKey = row.monthKey();
            assertTrue(row.year() >= 2000 && row.year() <= 2100, "year out of range");
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12,
                    "monthNumber out of range");
            assertNotNull(row.monthLabel(), "monthLabel must not be null");
            assertTrue(row.consultant() >= 0, "consultant must be non-negative");
            assertTrue(row.student() >= 0, "student must be non-negative");
            assertTrue(row.staff() >= 0, "staff must be non-negative");
            assertTrue(row.external() >= 0, "external must be non-negative");
            assertEquals(row.consultant() + row.student() + row.staff() + row.external(),
                    row.total(),
                    "total must equal consultant + student + staff + external");
        }
    }

    @Test
    void headcountByType_withCompanyFilter_unknownUuid_returnsEmpty() {
        List<ExecHeadcountByTypeMonthDTO> result =
                service.headcountByType(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUID must not match any real company");
    }
}
