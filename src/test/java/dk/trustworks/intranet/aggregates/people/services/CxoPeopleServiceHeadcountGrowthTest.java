package dk.trustworks.intranet.aggregates.people.services;

import dk.trustworks.intranet.aggregates.people.dto.cxo.HeadcountGrowthMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoPeopleService#headcountGrowth(Set)} mirroring
 * the BFF route at {@code /api/cxo/people/headcount-growth}.
 *
 * <p>Window is the trailing 24 months from {@code CURDATE()}. For each month,
 * counts users whose most recent {@code userstatus} row on or before month-end
 * has {@code status='ACTIVE'} and {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}.
 * {@code total} = consultant + student + staff (server-side sum).</p>
 */
@QuarkusTest
class CxoPeopleServiceHeadcountGrowthTest {

    @Inject
    CxoPeopleService service;

    @Test
    void headcountGrowth_noCompanyFilter_returnsList() {
        List<HeadcountGrowthMonthDTO> result = service.headcountGrowth(null);
        assertNotNull(result);
        String prevMonthKey = null;
        for (HeadcountGrowthMonthDTO row : result) {
            assertNotNull(row.monthKey(), "monthKey must not be null");
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.year() >= 2000 && row.year() <= 2100,
                    "year out of range: " + row.year());
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12,
                    "monthNumber out of range: " + row.monthNumber());
            assertNotNull(row.monthLabel(), "monthLabel must not be null");
            assertTrue(row.consultant() >= 0, "consultant must be non-negative");
            assertTrue(row.student() >= 0, "student must be non-negative");
            assertTrue(row.staff() >= 0, "staff must be non-negative");
            assertEquals(row.consultant() + row.student() + row.staff(), row.total(),
                    "total must equal consultant + student + staff");
            // Month order is ascending.
            if (prevMonthKey != null) {
                assertTrue(row.monthKey().compareTo(prevMonthKey) > 0,
                        "monthKey must be strictly ascending and unique: " + prevMonthKey
                                + " -> " + row.monthKey());
            }
            prevMonthKey = row.monthKey();
        }
    }

    @Test
    void headcountGrowth_withCompanyFilter_unknownUuid_returnsEmpty() {
        List<HeadcountGrowthMonthDTO> result =
                service.headcountGrowth(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUID must not match any real company");
    }
}
