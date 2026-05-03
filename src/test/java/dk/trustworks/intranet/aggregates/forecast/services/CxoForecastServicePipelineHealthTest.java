package dk.trustworks.intranet.aggregates.forecast.services;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthStageDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoForecastService#pipelineHealth(Set)} mirroring
 * the BFF route at {@code /api/cxo/forecast/pipeline-health}.
 *
 * <p>The shape test asserts row-level invariants without requiring fixture rows.
 * The companyIds-filter test uses random UUIDs that should not match any real
 * company, so the result must be empty (regression guard from Phase 1).</p>
 */
@QuarkusTest
class CxoForecastServicePipelineHealthTest {

    @Inject
    CxoForecastService service;

    @Test
    void pipelineHealth_noFilter_returnsValidShape() {
        List<PipelineHealthMonthDTO> result = service.pipelineHealth(null);
        assertNotNull(result);
        for (PipelineHealthMonthDTO m : result) {
            assertNotNull(m.month(), "month must not be null");
            assertTrue(m.month().matches("\\d{6}"), "month must be YYYYMM: " + m.month());
            assertNotNull(m.monthLabel(), "monthLabel must not be null");
            assertTrue(Double.isFinite(m.totalExpectedDkk()), "totalExpectedDkk must be finite");
            assertTrue(Double.isFinite(m.totalWeightedDkk()), "totalWeightedDkk must be finite");
            assertTrue(m.budgetTargetDkk() >= 0.0, "budgetTargetDkk must be non-negative");
            // SQL parity invariant: coverageRatio = totalWeighted / budgetTarget when the
            // target is positive, else 0 — both numerator (SUM of non-negative weighted
            // pipeline) and denominator are >= 0, so the ratio must be finite and >= 0.
            assertTrue(Double.isFinite(m.coverageRatio()) && m.coverageRatio() >= 0,
                    "coverageRatio must be finite and non-negative, was " + m.coverageRatio());
            assertNotNull(m.byStage(), "byStage must not be null");
            for (PipelineHealthStageDTO s : m.byStage()) {
                assertNotNull(s.stageId(), "stageId must not be null");
                assertTrue(Double.isFinite(s.expectedDkk()), "expectedDkk must be finite");
                assertTrue(Double.isFinite(s.weightedDkk()), "weightedDkk must be finite");
                assertTrue(s.opportunityCount() >= 0L, "opportunityCount must be non-negative");
            }
        }
    }

    @Test
    void pipelineHealth_withCompanyFilter_returnsEmpty() {
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<PipelineHealthMonthDTO> result = service.pipelineHealth(randomUuids);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUIDs must not match any real company");
    }
}
