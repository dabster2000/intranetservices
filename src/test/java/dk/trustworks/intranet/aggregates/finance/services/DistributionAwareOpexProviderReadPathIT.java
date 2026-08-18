package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the read-path of {@link DistributionAwareOpexProvider}.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md §5 (PR 2, Task 13)
 *
 * <p><b>Settlement-aware routing was removed on 2026-08-18.</b> EVERY month is now
 * served from {@code fact_opex_distribution_mat}, so the shared-services allocation
 * always applies and every row carries {@link OpexRow#SOURCE_DISTRIBUTION}. The old
 * "settled months read raw fact_opex_mat" branch silently deleted the allocation
 * rather than replacing it with a real GL charge — the settlement's accounts
 * (A/S 2170/2175, subsidiary 1350) are mapped IGNORE and never reach fact_opex, and
 * the vouchers were not even booked. See the provider's class note.
 *
 * <p>These tests now guard the opposite invariant: that nothing re-introduces a
 * per-month switch, and that the hot path still reads the materialized table.
 *
 * <p>Uses JUnit Jupiter assertions because AssertJ is not on this project's classpath.
 */
@QuarkusTest
class DistributionAwareOpexProviderReadPathIT {

    @Inject
    DistributionAwareOpexProvider provider;

    @Inject
    OpexDistributionRefreshService refreshService;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void seedMatTable() {
        refreshService.refresh();
    }

    @Test
    void unsettledMonthRange_returnsRowsFromDistributionMat() {
        // Current FY is fully unsettled (no INTERNAL_SERVICE invoices for it).
        YearMonth currentMonth = YearMonth.now();
        String mk = String.format("%04d%02d", currentMonth.getYear(), currentMonth.getMonthValue());

        List<OpexRow> rows = provider.getDistributionAwareOpex(
                mk, mk, null, null, null);

        assertFalse(rows.isEmpty(), "current month should return distribution rows");
        assertTrue(rows.stream().allMatch(r -> OpexRow.SOURCE_DISTRIBUTION.equals(r.dataSource())),
                "all current-month rows must be SOURCE_DISTRIBUTION");
    }

    @Test
    void settledMonth_stillCarriesTheAllocation() {
        // 2025-01 HAS a finalized INTERNAL_SERVICE invoice, so the old routing served
        // it raw from fact_opex_mat and the subsidiaries carried no shared-services
        // cost for it. It must now come from the distribution like every other month.
        List<OpexRow> rows = provider.getDistributionAwareOpex(
                "202501", "202501", null, null, null);

        if (!rows.isEmpty()) {
            assertTrue(rows.stream().allMatch(r -> OpexRow.SOURCE_DISTRIBUTION.equals(r.dataSource())),
                    "a settled month must still be allocated — the settlement never reaches fact_opex");
        }
    }

    @Test
    void mixedWindow_isUniformlyAllocated() {
        // Jan 2025 (settled under the old rule) → Jul 2025 (unsettled) — the boundary
        // that used to produce two different sources in one window. A window must no
        // longer change cost model halfway through.
        List<OpexRow> rows = provider.getDistributionAwareOpex(
                "202501", "202507", null, null, null);

        Set<String> sources = new HashSet<>();
        for (OpexRow r : rows) sources.add(r.dataSource());

        assertTrue(sources.size() <= 1,
                "one window must use one cost model, got sources=" + sources);
        for (OpexRow r : rows) {
            assertTrue(OpexRow.SOURCE_DISTRIBUTION.equals(r.dataSource()),
                    "every month must be allocated, including " + r.monthKey());
        }
    }

    @Test
    void filterPushdown_byCompanyIds_narrowsResult() {
        YearMonth currentMonth = YearMonth.now();
        String mk = String.format("%04d%02d", currentMonth.getYear(), currentMonth.getMonthValue());
        String trustworksAS = "d8894494-2fb4-4f72-9e05-e6032e6dd691";

        List<OpexRow> all = provider.getDistributionAwareOpex(
                mk, mk, null, null, null);
        List<OpexRow> filtered = provider.getDistributionAwareOpex(
                mk, mk, Set.of(trustworksAS), null, null);

        assertTrue(filtered.size() <= all.size(),
                "filtered must be a subset of unfiltered");
        assertTrue(filtered.stream().allMatch(r -> trustworksAS.equals(r.companyId())),
                "every filtered row must be for the requested company");
    }

    @Test
    @Transactional
    void emptyMatTable_unsettledMonth_returnsEmpty_notException() {
        // Wipe to simulate cold-start / failed-refresh edge case.
        em.createNativeQuery("DELETE FROM fact_opex_distribution_mat").executeUpdate();

        YearMonth currentMonth = YearMonth.now();
        String mk = String.format("%04d%02d", currentMonth.getYear(), currentMonth.getMonthValue());

        List<OpexRow> rows = provider.getDistributionAwareOpex(
                mk, mk, null, null, null);

        // Zero rows, NO exception. The freshness health check alerts on this state.
        assertTrue(rows.isEmpty(), "empty mat table must return empty list, not throw");

        // Restore the table so subsequent tests in the same run have data.
        refreshService.refresh();
    }
}
