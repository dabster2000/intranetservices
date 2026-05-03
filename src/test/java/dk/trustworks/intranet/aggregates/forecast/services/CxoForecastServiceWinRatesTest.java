package dk.trustworks.intranet.aggregates.forecast.services;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateDealTypeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRatePracticeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateStageDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoForecastService#winRates(Set)} mirroring the
 * BFF route at {@code /api/cxo/forecast/win-rates}.
 *
 * <p>The shape test asserts row-level invariants without requiring fixture rows;
 * the for-loop is a no-op on empty fixtures but enforces invariants when data
 * exists. Unlike the other CXO endpoints, {@code companyIds} is intentionally
 * <em>ignored</em> by this endpoint because {@code fact_historical_win_rates}
 * has no {@code company_uuid} column (BFF route lines 61-63 document this).
 * The companyIds-filter test asserts the result is the same shape as the
 * unfiltered call, NOT that it is empty.</p>
 */
@QuarkusTest
class CxoForecastServiceWinRatesTest {

    private static final Set<String> KNOWN_STAGES =
            Set.of("DETECTED", "QUALIFIED", "SHORTLISTED", "PROPOSAL", "NEGOTIATION");

    @Inject
    CxoForecastService service;

    @Test
    void winRates_noFilter_returnsValidShape() {
        List<WinRateStageDTO> result = service.winRates(null);
        assertNotNull(result);
        for (WinRateStageDTO stage : result) {
            assertNotNull(stage.stageId(), "stageId must not be null");
            assertTrue(KNOWN_STAGES.contains(stage.stageId()),
                    "Unexpected stageId: " + stage.stageId());
            assertNotNull(stage.stageLabel(), "stageLabel must not be null");
            assertTrue(stage.sampleSize() >= 0L, "sampleSize must be non-negative");
            assertTrue(stage.wonCount() >= 0L, "wonCount must be non-negative");
            assertTrue(stage.reachedCount() >= 0L, "reachedCount must be non-negative");
            assertTrue(stage.wonCount() <= stage.reachedCount(),
                    "wonCount must not exceed reachedCount");
            assertNotNull(stage.byPractice(), "byPractice must not be null");
            for (WinRatePracticeDTO p : stage.byPractice()) {
                assertNotNull(p.practice(), "practice must not be null");
                assertTrue(p.sampleSize() >= 0L, "practice sampleSize must be non-negative");
            }
            assertNotNull(stage.byDealType(), "byDealType must not be null");
            for (WinRateDealTypeDTO d : stage.byDealType()) {
                assertNotNull(d.dealType(), "dealType must not be null");
                assertTrue(d.sampleSize() >= 0L, "dealType sampleSize must be non-negative");
            }
        }
    }

    @Test
    void winRates_withCompanyFilter_ignoresFilter() {
        // companyIds is accepted but ignored — fact_historical_win_rates has no
        // company_uuid column. The filtered result must match the unfiltered one.
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<WinRateStageDTO> filtered = service.winRates(randomUuids);
        List<WinRateStageDTO> unfiltered = service.winRates(null);
        assertNotNull(filtered);
        assertEquals(unfiltered.size(), filtered.size(),
                "companyIds is ignored — filtered result must mirror unfiltered");
    }
}
