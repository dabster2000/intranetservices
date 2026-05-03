package dk.trustworks.intranet.aggregates.people.services;

import dk.trustworks.intranet.aggregates.people.dto.cxo.TurnoverTtmMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoPeopleService#turnoverTtm(Set)} mirroring
 * the BFF route at {@code /api/cxo/people/turnover-ttm}.
 *
 * <p>Window is the trailing 24 months from {@code CURDATE()} on
 * {@code userstatus.statusdate}, type {@code IN ('CONSULTANT', 'STUDENT', 'STAFF')}.
 * Counts are non-negative; {@code net = hires - terminations} can be negative.</p>
 */
@QuarkusTest
class CxoPeopleServiceTurnoverTtmTest {

    @Inject
    CxoPeopleService service;

    @Test
    void turnoverTtm_noCompanyFilter_returnsList() {
        List<TurnoverTtmMonthDTO> result = service.turnoverTtm(null);
        assertNotNull(result);
        for (TurnoverTtmMonthDTO row : result) {
            assertNotNull(row.monthKey(), "monthKey must not be null");
            assertEquals(6, row.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(row.year() >= 2000 && row.year() <= 2100, "year out of range: " + row.year());
            assertTrue(row.monthNumber() >= 1 && row.monthNumber() <= 12,
                    "monthNumber out of range: " + row.monthNumber());
            assertNotNull(row.monthLabel(), "monthLabel must not be null");
            // SUM(CASE ...) cannot be negative.
            assertTrue(row.hires() >= 0, "hires must be non-negative: " + row.hires());
            assertTrue(row.terminations() >= 0, "terminations must be non-negative: " + row.terminations());
            // net = hires - terminations
            assertEquals(row.hires() - row.terminations(), row.net(),
                    "net must equal hires - terminations");
        }
    }

    @Test
    void turnoverTtm_withCompanyFilter_unknownUuid_returnsEmpty() {
        List<TurnoverTtmMonthDTO> result =
                service.turnoverTtm(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        // Random UUID must not match any real company.
        assertTrue(result.isEmpty(), "Random UUID must not match any real company");
    }
}
