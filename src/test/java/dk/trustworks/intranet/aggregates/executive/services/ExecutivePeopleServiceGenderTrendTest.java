package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecGenderTrendMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link ExecutivePeopleService#genderTrend(Set)}
 * mirroring the BFF route at {@code /api/executive/gender-trend}.
 *
 * <p>Trailing 24 months from {@code CURDATE()} on {@code userstatus.statusdate}
 * for active employees ({@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}).
 * {@code femalePct} is computed server-side excluding unknown-gender from
 * the denominator; {@code null} when no MALE/FEMALE in that month.</p>
 */
@QuarkusTest
class ExecutivePeopleServiceGenderTrendTest {

    @Inject
    ExecutivePeopleService service;

    @Test
    void genderTrend_noCompanyFilter_returnsList() {
        List<ExecGenderTrendMonthDTO> result = service.genderTrend(null);
        assertNotNull(result);
        String prevKey = "";
        for (ExecGenderTrendMonthDTO row : result) {
            assertNotNull(row.monthKey(), "monthKey must not be null");
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.monthKey().compareTo(prevKey) >= 0,
                    "monthKey must be ascending: prev=" + prevKey + " current=" + row.monthKey());
            prevKey = row.monthKey();
            assertTrue(row.year() >= 2000 && row.year() <= 2100, "year out of range");
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12,
                    "monthNumber out of range");
            assertNotNull(row.monthLabel(), "monthLabel must not be null");
            assertTrue(row.maleCount() >= 0, "maleCount must be non-negative");
            assertTrue(row.femaleCount() >= 0, "femaleCount must be non-negative");
            assertTrue(row.unknownCount() >= 0, "unknownCount must be non-negative");
            // femalePct can be null when denominator is zero, otherwise in [0,100]
            if (row.femalePct() != null) {
                assertTrue(row.femalePct() >= 0.0 && row.femalePct() <= 100.0,
                        "femalePct must be in [0, 100]: " + row.femalePct());
            } else {
                // null only when both maleCount and femaleCount are 0
                assertEquals(0L, row.maleCount() + row.femaleCount(),
                        "femalePct=null only when male+female=0");
            }
        }
    }

    @Test
    void genderTrend_withCompanyFilter_unknownUuid_returnsEmpty() {
        List<ExecGenderTrendMonthDTO> result =
                service.genderTrend(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUID must not match any real company");
    }
}
