package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService.RefreshOutcome;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link OpexDistributionRefreshService}.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md §4
 *
 * <p>Asserts three properties of {@code refresh()}:
 * <ol>
 *   <li>It inserts rows for the current fiscal year window.</li>
 *   <li>It is idempotent — a second call yields the same row count
 *       and reports {@code deleted == first.inserted}.</li>
 *   <li>The window respects the configured {@code fyBack} property —
 *       i.e. the minimum {@code month_key} written is no earlier than
 *       {@code currentFiscalYearStart - fyBack years}.</li>
 * </ol>
 */
@QuarkusTest
class OpexDistributionRefreshServiceIT {

    @Inject
    OpexDistributionRefreshService refreshService;

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void refresh_populatesTableForCurrentFiscalYear() {
        RefreshOutcome outcome = refreshService.refresh();

        assertTrue(outcome.inserted() > 0,
                "expected refresh to insert rows, but inserted=" + outcome.inserted());
        assertTrue(outcome.took().toMillis() > 0L,
                "expected non-zero refresh duration, but took=" + outcome.took());

        long rowCount = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM fact_opex_distribution_mat")
                .getSingleResult()).longValue();
        assertEquals(outcome.inserted(), rowCount,
                "row count in table must equal RefreshOutcome.inserted()");
    }

    @Test
    @Transactional
    void refresh_isIdempotent_secondCallProducesIdenticalRowCount() {
        RefreshOutcome first = refreshService.refresh();
        RefreshOutcome second = refreshService.refresh();

        assertEquals(first.inserted(), second.inserted(),
                "second refresh must insert the same number of rows as the first");
        assertEquals(first.inserted(), second.deleted(),
                "second refresh must delete exactly what the first inserted");
    }

    @Test
    @Transactional
    void refresh_windowRespectsConfiguredFyBack() {
        refreshService.refresh();

        YearMonth currentFyStart = YearMonth.from(
                UtilizationCalculationHelper.getCurrentFiscalYearRange().start());
        YearMonth windowFloor = currentFyStart.minusYears(refreshService.fyBack);

        String minMonthKey = (String) em.createNativeQuery(
                        "SELECT MIN(month_key) FROM fact_opex_distribution_mat")
                .getSingleResult();

        String windowFloorKey = String.format("%04d%02d",
                windowFloor.getYear(), windowFloor.getMonthValue());
        assertTrue(minMonthKey.compareTo(windowFloorKey) >= 0,
                "minimum month_key " + minMonthKey
                        + " must not precede window floor " + windowFloorKey);
    }
}
