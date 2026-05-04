package dk.trustworks.intranet.aggregates.forecast.services;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffPracticeDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoForecastService#contractRunoff(Set)} mirroring
 * the BFF route at {@code /api/cxo/forecast/contract-runoff}.
 *
 * <p>The shape test asserts row-level invariants without requiring fixture rows:
 * the for-loop is a no-op on empty fixtures but enforces invariants when data
 * exists. The companyIds-filter test uses random UUIDs that should not match any
 * real company, so the result must be empty — this is the regression guard from
 * Phase 1 (random UUIDs catch SQL filter regressions that earlier
 * "doesNotThrow"-only tests miss).</p>
 */
@QuarkusTest
class CxoForecastServiceContractRunoffTest {

    @Inject
    CxoForecastService service;

    @Test
    void contractRunoff_noFilter_returnsValidShape() {
        List<ContractRunoffMonthDTO> result = service.contractRunoff(null);
        assertNotNull(result);
        for (ContractRunoffMonthDTO m : result) {
            assertNotNull(m.month(), "month must not be null");
            assertTrue(m.month().matches("\\d{6}"), "month must be YYYYMM: " + m.month());
            assertNotNull(m.monthLabel(), "monthLabel must not be null");
            // SQL parity invariant: both revenue components are SUM/CASE aggregates over
            // monthly_revenue_dkk (strictly >= 0), so neither can go negative.
            assertTrue(m.activeRevenueDkk() >= 0 && m.expiringRevenueDkk() >= 0,
                    "active and expiring revenue must both be non-negative");
            assertTrue(m.expiringContractCount() >= 0L, "expiringContractCount must be non-negative");
            assertTrue(m.newRevenueDkk() >= 0.0, "newRevenueDkk must be non-negative");
            assertTrue(m.extensionRevenueDkk() >= 0.0, "extensionRevenueDkk must be non-negative");
            assertNotNull(m.byPractice(), "byPractice must not be null");
            for (ContractRunoffPracticeDTO p : m.byPractice()) {
                assertNotNull(p.practice(), "practice must not be null");
                assertTrue(p.revenueDkk() >= 0.0, "practice revenueDkk must be non-negative");
            }
        }
    }

    @Test
    void contractRunoff_withCompanyFilter_returnsEmpty() {
        Set<String> randomUuids = Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002");
        List<ContractRunoffMonthDTO> result = service.contractRunoff(randomUuids);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Random UUIDs must not match any real company");
    }
}
