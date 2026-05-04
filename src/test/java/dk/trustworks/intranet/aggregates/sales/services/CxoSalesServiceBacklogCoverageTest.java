package dk.trustworks.intranet.aggregates.sales.services;

import dk.trustworks.intranet.aggregates.sales.dto.cxo.BacklogCoverageMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoSalesService#backlogCoverage(Set)} mirroring
 * the BFF route at {@code /api/cxo/sales/backlog-coverage}.
 *
 * <p>Window is forward-looking: {@code delivery_month_key >= currentMonthKey}.
 * The shape test asserts every row's monthKey is at or after today's YYYYMM and
 * fields are non-negative; the for-loop is a safe no-op on empty fixtures.</p>
 */
@QuarkusTest
class CxoSalesServiceBacklogCoverageTest {

    @Inject
    CxoSalesService service;

    @Test
    void backlogCoverage_noFilter_returnsValidShape() {
        LocalDate today = LocalDate.now();
        String currentMonthKey = String.format("%04d%02d", today.getYear(), today.getMonthValue());

        List<BacklogCoverageMonthDTO> result = service.backlogCoverage(null);
        assertNotNull(result);
        for (BacklogCoverageMonthDTO m : result) {
            assertNotNull(m.monthKey(), "monthKey must not be null");
            assertEquals(6, m.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(m.monthKey().compareTo(currentMonthKey) >= 0,
                    "monthKey must be >= current month: " + m.monthKey());
            assertTrue(m.year() >= 2020 && m.year() <= 2100, "year out of range: " + m.year());
            assertTrue(m.monthNumber() >= 1 && m.monthNumber() <= 12,
                    "monthNumber out of range: " + m.monthNumber());
            assertNotNull(m.monthLabel(), "monthLabel must not be null");
            // SQL parity invariant: backlog revenue is COALESCE(SUM(...), 0) of strictly
            // non-negative monthly fees from fact_backlog, so it can never go negative.
            assertTrue(m.backlogRevenueDkk() >= 0, "backlog revenue must be non-negative");
            assertTrue(m.consultantCount() >= 0.0, "consultantCount must be non-negative");
        }
    }

    @Test
    void backlogCoverage_withCompanyFilter_returnsEmpty() {
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<BacklogCoverageMonthDTO> result = service.backlogCoverage(randomUuids);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUIDs must not match any real company");
    }
}
