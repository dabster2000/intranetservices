package dk.trustworks.intranet.aggregates.finance.services;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * F3 — prod-safety guard: the internal-cost timing alignment flag MUST default to {@code false}.
 *
 * <p>When OFF, {@code getExpectedAccumulatedEBITDA} never builds a re-timing map and the monthly
 * direct cost is byte-identical to the GL-expensedate behaviour shipped today. This test runs under
 * the default config profile (no override) and pins the default so a stray {@code true} can never
 * reach production unnoticed.
 */
@QuarkusTest
class CxoFinanceInternalCostTimingFlagTest {

    @Test
    void flag_defaultsToFalse_prodSafe() {
        boolean enabled = ConfigProvider.getConfig()
                .getValue("finance.internal-cost-timing-alignment.enabled", Boolean.class);

        assertFalse(enabled,
                "finance.internal-cost-timing-alignment.enabled MUST default to false — the EBITDA "
                + "chart stays byte-identical to today until validated on a closed FY in staging.");
    }
}
