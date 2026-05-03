package dk.trustworks.intranet.aggregates.sales.services;

import dk.trustworks.intranet.aggregates.sales.dto.cxo.PipelineTrendMonthDTO;
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
 * Service-level tests for {@link CxoSalesService#pipelineTrend(Set)} mirroring
 * the BFF route at {@code /api/cxo/sales/pipeline-trend}.
 *
 * <p>Window is forward-looking 12 months: from current month through current
 * month + 11. fact_pipeline only stores open leads with future-dated delivery
 * months, so a trailing window would always return empty results.</p>
 */
@QuarkusTest
class CxoSalesServicePipelineTrendTest {

    @Inject
    CxoSalesService service;

    @Test
    void pipelineTrend_noFilter_returnsValidShape() {
        LocalDate today = LocalDate.now();
        LocalDate toDate = today.withDayOfMonth(1).plusMonths(11);
        String fromMonthKey = String.format("%04d%02d", today.getYear(), today.getMonthValue());
        String toMonthKey = String.format("%04d%02d", toDate.getYear(), toDate.getMonthValue());

        List<PipelineTrendMonthDTO> result = service.pipelineTrend(null);
        assertNotNull(result);
        for (PipelineTrendMonthDTO m : result) {
            assertNotNull(m.monthKey(), "monthKey must not be null");
            assertEquals(6, m.monthKey().length(), "monthKey must be YYYYMM");
            assertTrue(m.monthKey().compareTo(fromMonthKey) >= 0,
                    "monthKey must be >= fromMonthKey: " + m.monthKey());
            assertTrue(m.monthKey().compareTo(toMonthKey) <= 0,
                    "monthKey must be <= toMonthKey: " + m.monthKey());
            assertTrue(m.year() >= 2020 && m.year() <= 2100, "year out of range: " + m.year());
            assertTrue(m.monthNumber() >= 1 && m.monthNumber() <= 12,
                    "monthNumber out of range: " + m.monthNumber());
            assertNotNull(m.monthLabel(), "monthLabel must not be null");
            // SQL parity invariant: weighted pipeline is COALESCE(SUM(weighted_pipeline_dkk), 0)
            // of strictly non-negative pipeline fees, so it can never go negative.
            assertTrue(m.weightedPipelineDkk() >= 0,
                    "weightedPipelineDkk must be non-negative");
        }
    }

    @Test
    void pipelineTrend_withCompanyFilter_returnsEmpty() {
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<PipelineTrendMonthDTO> result = service.pipelineTrend(randomUuids);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUIDs must not match any real company");
    }
}
