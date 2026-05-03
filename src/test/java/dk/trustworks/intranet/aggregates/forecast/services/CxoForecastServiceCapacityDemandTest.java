package dk.trustworks.intranet.aggregates.forecast.services;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.CapacityDemandMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.CapacityDemandPracticeDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoForecastService#capacityDemand(Set)}
 * mirroring the BFF route at {@code /api/cxo/forecast/capacity-demand}.
 *
 * <p>The time series window is current month + 12 forward (= 13 months
 * total). Same as {@code revenueForecast}: the cursor walks deterministically
 * and emits zero-valued months when no underlying row matches, so the
 * filter test asserts {@code size == 13}.</p>
 *
 * <p>The shape test asserts row-level invariants: month format, finite
 * numeric values, and the {@code totalDemandFte = backlogDemandFte +
 * pipelineDemandFte} identity (within rounding tolerance because all
 * three are rounded to 1 decimal place independently).</p>
 */
@QuarkusTest
class CxoForecastServiceCapacityDemandTest {

    private static final int EXPECTED_MONTHS = 13;
    /** Tolerance for the totalDemand-vs-sum check. Each component is rounded to 0.1 so the sum can drift up to 0.15. */
    private static final double ROUNDING_TOLERANCE = 0.15;

    @Inject
    CxoForecastService service;

    @Test
    void capacityDemand_noFilter_returnsValidShape() {
        List<CapacityDemandMonthDTO> result = service.capacityDemand(null);
        assertNotNull(result);
        assertEquals(EXPECTED_MONTHS, result.size(),
                "Time series window is current + 12 forward = 13 months");
        for (CapacityDemandMonthDTO m : result) {
            assertNotNull(m.month(), "month must not be null");
            assertTrue(m.month().matches("\\d{6}"), "month must be YYYYMM: " + m.month());
            assertNotNull(m.monthLabel(), "monthLabel must not be null");
            assertTrue(Double.isFinite(m.capacityFte()), "capacityFte must be finite");
            assertTrue(Double.isFinite(m.backlogDemandFte()), "backlogDemandFte must be finite");
            assertTrue(Double.isFinite(m.pipelineDemandFte()), "pipelineDemandFte must be finite");
            assertTrue(Double.isFinite(m.totalDemandFte()), "totalDemandFte must be finite");
            assertTrue(Double.isFinite(m.gapFte()), "gapFte must be finite");
            // totalDemand = backlog + pipeline (each rounded to 1dp independently)
            assertEquals(m.backlogDemandFte() + m.pipelineDemandFte(),
                    m.totalDemandFte(), ROUNDING_TOLERANCE,
                    "totalDemandFte should equal backlogDemandFte + pipelineDemandFte (within rounding)");
            assertNotNull(m.byPractice(), "byPractice must not be null");
            for (CapacityDemandPracticeDTO p : m.byPractice()) {
                assertNotNull(p.practice(), "practice must not be null");
                assertTrue(Double.isFinite(p.capacityFte()), "practice capacityFte must be finite");
                assertTrue(Double.isFinite(p.demandFte()), "practice demandFte must be finite");
                assertTrue(Double.isFinite(p.gapFte()), "practice gapFte must be finite");
            }
        }
    }

    @Test
    void capacityDemand_withCompanyFilter_returnsAllZeroWindow() {
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<CapacityDemandMonthDTO> result = service.capacityDemand(randomUuids);
        assertNotNull(result);
        assertEquals(EXPECTED_MONTHS, result.size(),
                "Time series is generated even when no underlying rows match — 13 zero-valued months");
        for (CapacityDemandMonthDTO m : result) {
            assertEquals(0.0, m.capacityFte(), 1e-9, "capacityFte must be zero for unmatched filter");
            assertEquals(0.0, m.backlogDemandFte(), 1e-9, "backlogDemandFte must be zero for unmatched filter");
            assertEquals(0.0, m.pipelineDemandFte(), 1e-9, "pipelineDemandFte must be zero for unmatched filter");
            assertEquals(0.0, m.totalDemandFte(), 1e-9, "totalDemandFte must be zero for unmatched filter");
            assertEquals(0.0, m.gapFte(), 1e-9, "gapFte must be zero for unmatched filter");
            assertTrue(m.byPractice().isEmpty(), "byPractice must be empty for unmatched filter");
        }
    }
}
