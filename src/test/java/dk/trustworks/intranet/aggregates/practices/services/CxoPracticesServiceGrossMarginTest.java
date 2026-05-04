package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticesGrossMarginMonthDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CxoPracticesService#grossMargin(Set)} mirroring
 * the BFF route at {@code /api/cxo/practices/gross-margin}.
 *
 * <p>Always returns 5 rows in fixed order: PM, BA, CYB, DEV, SA. Margin %s are
 * nullable (Double) — {@code null} when revenue is 0; {@code marginDeltaPts}
 * is null when either side is null. Non-null margins are percentage points
 * (e.g. 35.5 = 35.5%).</p>
 */
@QuarkusTest
class CxoPracticesServiceGrossMarginTest {

    @Inject
    CxoPracticesService service;

    private static final List<String> EXPECTED_PRACTICES =
            List.of("PM", "BA", "CYB", "DEV", "SA");

    @Test
    void grossMargin_noCompanyFilter_returnsList() {
        List<PracticesGrossMarginMonthDTO> result = service.grossMargin(null);
        assertNotNull(result);
        assertEquals(5, result.size(), "grossMargin must return exactly 5 practices");

        // Practices must appear in the fixed canonical order.
        for (int i = 0; i < EXPECTED_PRACTICES.size(); i++) {
            assertEquals(EXPECTED_PRACTICES.get(i), result.get(i).practiceId(),
                    "Practice at index " + i + " must be " + EXPECTED_PRACTICES.get(i));
        }

        for (PracticesGrossMarginMonthDTO row : result) {
            assertNotNull(row.practiceId(), "practiceId must not be null");
            assertTrue(EXPECTED_PRACTICES.contains(row.practiceId()),
                    "practiceId must be one of " + EXPECTED_PRACTICES + ", was " + row.practiceId());

            // Numeric DKK fields are non-negative SUMs (revenue can dip to 0 or below
            // if credit-notes outweigh invoices in a window, but in practice the test
            // bound checks just non-NaN — values can be any finite double).
            assertTrue(Double.isFinite(row.currentRevenueDkk()),
                    "currentRevenueDkk must be finite: " + row.currentRevenueDkk());
            assertTrue(Double.isFinite(row.currentCostDkk()),
                    "currentCostDkk must be finite: " + row.currentCostDkk());
            assertTrue(Double.isFinite(row.priorRevenueDkk()),
                    "priorRevenueDkk must be finite: " + row.priorRevenueDkk());
            assertTrue(Double.isFinite(row.priorCostDkk()),
                    "priorCostDkk must be finite: " + row.priorCostDkk());

            // Margin % nullability rules:
            //   currentMarginPct  = null IFF currentRevenueDkk  <= 0
            //   priorMarginPct    = null IFF priorRevenueDkk    <= 0
            //   marginDeltaPts    = null IFF either margin is null
            if (row.currentRevenueDkk() > 0) {
                assertNotNull(row.currentMarginPct(),
                        "currentMarginPct must be non-null when currentRevenueDkk > 0");
                assertTrue(Double.isFinite(row.currentMarginPct()),
                        "currentMarginPct must be finite: " + row.currentMarginPct());
            }
            if (row.priorRevenueDkk() > 0) {
                assertNotNull(row.priorMarginPct(),
                        "priorMarginPct must be non-null when priorRevenueDkk > 0");
                assertTrue(Double.isFinite(row.priorMarginPct()),
                        "priorMarginPct must be finite: " + row.priorMarginPct());
            }
            if (row.currentMarginPct() != null && row.priorMarginPct() != null) {
                assertNotNull(row.marginDeltaPts(),
                        "marginDeltaPts must be non-null when both margins are non-null");
                assertEquals(
                        row.currentMarginPct() - row.priorMarginPct(),
                        row.marginDeltaPts(),
                        1e-9,
                        "marginDeltaPts must equal currentMarginPct - priorMarginPct");
            }
        }
    }

    @Test
    void grossMargin_withCompanyFilter_unknownUuid_returnsNonNull() {
        // Random UUID will not match any real company; both revenue and OPEX
        // collapse to 0 for every practice. Series shape (5 rows in fixed order)
        // must be preserved.
        List<PracticesGrossMarginMonthDTO> result =
                service.grossMargin(Set.of("00000000-0000-0000-0000-000000000001"));
        assertNotNull(result);
        assertEquals(5, result.size(), "Series shape must be 5 practices even when filter matches no rows");

        for (int i = 0; i < EXPECTED_PRACTICES.size(); i++) {
            PracticesGrossMarginMonthDTO row = result.get(i);
            assertEquals(EXPECTED_PRACTICES.get(i), row.practiceId());

            // No matching companies → 0 revenue + 0 cost in both windows.
            assertEquals(0d, row.currentRevenueDkk(), 1e-9);
            assertEquals(0d, row.currentCostDkk(), 1e-9);
            assertEquals(0d, row.priorRevenueDkk(), 1e-9);
            assertEquals(0d, row.priorCostDkk(), 1e-9);

            // Both windows have zero revenue → both margins null → delta null.
            assertEquals(null, row.currentMarginPct(),
                    "0 revenue → currentMarginPct must be null");
            assertEquals(null, row.priorMarginPct(),
                    "0 revenue → priorMarginPct must be null");
            assertEquals(null, row.marginDeltaPts(),
                    "Either margin null → marginDeltaPts must be null");
        }
    }
}
