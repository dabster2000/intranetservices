package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.FiscalYearData;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity test: asserts that what the refresh batchlet writes into
 * {@code fact_opex_distribution_mat} is bit-for-bit identical to what the
 * in-memory distribution algorithm produces from the same {@code FiscalYearData}.
 *
 * <p>Since PR 2 moved {@code computeDistributionForMonth(s)} from the provider
 * into {@link OpexDistributionRefreshService}, this test now verifies that the
 * INSERT path doesn't corrupt the algorithm output — i.e. the rows we write to
 * the table aggregate (per {company,costCenter,expenseCategory,isPayroll}) to
 * the same totals the in-memory algorithm produces.
 *
 * <p>For each month in the current fiscal year:
 * <ol>
 *   <li>Run the algorithm directly via {@code computeDistributionForMonths}</li>
 *   <li>Run the refresh service (DELETE + INSERT)</li>
 *   <li>Read back the materialized rows</li>
 *   <li>Assert (company, cost_center, expense_category, is_payroll) → amount maps match</li>
 * </ol>
 *
 * <p>Guards against future regressions in the bulk-insert mapping layer
 * (column order, decimal scaling, monthKey conversion, etc.).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md §7 #1
 */
@QuarkusTest
class OpexDistributionRefreshParityIT {

    @Inject
    IntercompanyCalcService intercompanyCalcService;

    @Inject
    OpexDistributionRefreshService refreshService;

    @Inject
    EntityManager em;

    @ConfigProperty(
            name = "dk.trustworks.intranet.aggregates.accounting.salary-buffer-multiplier",
            defaultValue = "1.02")
    double salaryBufferMultiplier;

    @Test
    @Transactional
    void materialized_rows_match_live_compute_for_every_month_in_current_fy() {
        var currentFy = UtilizationCalculationHelper.getCurrentFiscalYearRange();
        LocalDate fyStart = currentFy.start();
        LocalDate fyEnd = currentFy.end().plusDays(1);

        FiscalYearData fyData = intercompanyCalcService.loadFiscalYear(
                fyStart, fyEnd, salaryBufferMultiplier);

        List<YearMonth> months = new ArrayList<>(fyData.perMonth.keySet());
        List<OpexRow> liveRows = refreshService.computeDistributionForMonths(months, fyData);

        Map<YearMonth, Map<String, Double>> live = new TreeMap<>();
        for (OpexRow r : liveRows) {
            YearMonth ym = YearMonth.parse(r.monthKey(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            live.computeIfAbsent(ym, k -> new HashMap<>())
                .merge(dimensionKey(r), roundToCents(r.opexAmountDkk()), Double::sum);
        }

        // Guard: a sparse test DB would otherwise make this test pass vacuously.
        int totalLiveRows = live.values().stream().mapToInt(Map::size).sum();
        assertTrue(totalLiveRows > 0,
                "Live algorithm produced zero rows across the current FY — parity test "
                + "would pass vacuously. Source data may be missing from the test database.");

        refreshService.refresh();

        Map<YearMonth, Map<String, Double>> mat = new TreeMap<>();
        for (YearMonth ym : live.keySet()) {
            mat.put(ym, readMatTableForMonth(ym));
        }

        for (YearMonth ym : live.keySet()) {
            Map<String, Double> liveMonth = live.get(ym);
            Map<String, Double> matMonth = mat.get(ym);
            assertEquals(liveMonth.keySet(), matMonth.keySet(),
                    "Dimension keys differ for " + ym);
            for (String key : liveMonth.keySet()) {
                assertEquals(liveMonth.get(key), matMonth.get(key), 0.01,
                        "Amount differs for " + key + " @ " + ym);
            }
        }
    }

    private static String dimensionKey(OpexRow r) {
        return r.companyId() + "|" + r.costCenterId() + "|"
                + r.expenseCategoryId() + "|" + r.isPayrollFlag();
    }

    /** Rounded to 2 decimal places (HALF_EVEN) to match what the DB performs
     *  at INSERT time for DECIMAL(14,2). Keeps live-vs-materialized apples-to-apples. */
    private static double roundToCents(double amount) {
        return BigDecimal.valueOf(amount)
                .setScale(2, RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> readMatTableForMonth(YearMonth ym) {
        String monthKey = UtilizationCalculationHelper.toMonthKey(
                ym.getYear(), ym.getMonthValue());

        List<Tuple> rows = em.createNativeQuery(
                "SELECT company_id, cost_center_id, expense_category_id, " +
                        "       is_payroll_flag, SUM(opex_amount_dkk) AS amt " +
                        "FROM fact_opex_distribution_mat " +
                        "WHERE month_key = :mk " +
                        "GROUP BY company_id, cost_center_id, expense_category_id, is_payroll_flag",
                Tuple.class)
                .setParameter("mk", monthKey)
                .getResultList();

        Map<String, Double> out = new HashMap<>();
        for (Tuple t : rows) {
            String key = t.get("company_id") + "|"
                    + t.get("cost_center_id") + "|"
                    + t.get("expense_category_id") + "|"
                    + (((Number) t.get("is_payroll_flag")).intValue() == 1);
            out.put(key, ((Number) t.get("amt")).doubleValue());
        }
        return out;
    }
}
