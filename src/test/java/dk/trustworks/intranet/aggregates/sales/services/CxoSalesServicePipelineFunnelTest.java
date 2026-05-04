package dk.trustworks.intranet.aggregates.sales.services;

import dk.trustworks.intranet.aggregates.sales.dto.cxo.PipelineFunnelStageDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoSalesService#pipelineFunnel(Set)} mirroring the
 * BFF route at {@code /api/cxo/sales/pipeline-funnel}.
 *
 * <p>The shape test asserts row-level invariants without requiring fixture rows: the
 * for-loop over the result list is a no-op on empty fixtures but enforces invariants
 * when data exists. The companyIds-filter test uses random UUIDs that should not
 * match any real company, so the result must be empty — this is the regression
 * guard from Phase 1 (random UUIDs caught a SQL filter regression that earlier
 * "doesNotThrow"-only tests missed).</p>
 */
@QuarkusTest
class CxoSalesServicePipelineFunnelTest {

    private static final Set<String> KNOWN_STAGES =
            Set.of("DETECTED", "QUALIFIED", "SHORTLISTED", "PROPOSAL", "NEGOTIATION");

    @Inject
    CxoSalesService service;

    @Test
    void pipelineFunnel_noFilter_returnsValidShape() {
        List<PipelineFunnelStageDTO> result = service.pipelineFunnel(null);
        assertNotNull(result);
        for (PipelineFunnelStageDTO stage : result) {
            assertNotNull(stage.stageId(), "stageId must not be null");
            assertTrue(KNOWN_STAGES.contains(stage.stageId()),
                    "Unexpected stageId: " + stage.stageId());
            assertNotNull(stage.stageLabel(), "stageLabel must not be null");
            assertTrue(stage.expectedRevenueDkk() >= 0.0,
                    "expectedRevenueDkk must be non-negative");
            assertTrue(stage.weightedPipelineDkk() >= 0.0,
                    "weightedPipelineDkk must be non-negative");
            assertTrue(stage.opportunityCount() >= 0L,
                    "opportunityCount must be non-negative");
        }
        // SQL parity invariant: weighted pipeline is expected revenue scaled by stage probability,
        // so the sum of weighted values must always be at most the sum of expected values.
        double totalExpected = result.stream().mapToDouble(PipelineFunnelStageDTO::expectedRevenueDkk).sum();
        double totalWeighted = result.stream().mapToDouble(PipelineFunnelStageDTO::weightedPipelineDkk).sum();
        assertTrue(totalWeighted <= totalExpected + 1e-6,
                "Sum of weightedPipelineDkk must be <= sum of expectedRevenueDkk");
    }

    @Test
    void pipelineFunnel_withCompanyFilter_returnsEmpty() {
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<PipelineFunnelStageDTO> result = service.pipelineFunnel(randomUuids);
        assertNotNull(result);
        assertEquals(0, result.size(), "Random UUIDs must not match any real company");
    }
}
