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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity test: asserts that what the refresh batchlet writes into
 * {@code fact_opex_distribution_mat} is bit-for-bit identical to what
 * the live {@link DistributionAwareOpexProvider#computeDistributionForMonth}
 * produces today.
 *
 * <p>For each month in the current fiscal year:
 * <ol>
 *   <li>Run the live algorithm directly</li>
 *   <li>Run the refresh service</li>
 *   <li>Read back the materialized rows</li>
 *   <li>Assert (company, cost_center, expense_category, is_payroll) → amount maps match</li>
 * </ol>
 *
 * <p>Guards against future algorithm drift — if anyone later changes one path
 * but not the other, this test fails loudly.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md §7 #1
 */
@QuarkusTest
class OpexDistributionRefreshParityIT {

    @Inject
    IntercompanyCalcService intercompanyCalcService;

    @Inject
    DistributionAwareOpexProvider opexProvider;

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
        // 1. Snapshot live algorithm output for the current FY
        LocalDate fyStart = UtilizationCalculationHelper.getCurrentFiscalYearRange().start();
        LocalDate fyEnd = UtilizationCalculationHelper.getCurrentFiscalYearRange().end().plusDays(1);

        FiscalYearData fyData = intercompanyCalcService.loadFiscalYear(
                fyStart, fyEnd, salaryBufferMultiplier);

        Map<YearMonth, Map<String, Double>> live = new TreeMap<>();
        for (YearMonth ym : fyData.perMonth.keySet()) {
            List<OpexRow> liveRows = opexProvider.computeDistributionForMonth(
                    ym,
                    fyData.perMonth.get(ym),
                    fyData.lumpsByMonth.getOrDefault(ym, Collections.emptyMap()));
            live.put(ym, aggregate(liveRows));
        }

        // 2. Run the refresh
        refreshService.refresh();

        // 3. Read back materialized rows by month and aggregate the same way
        Map<YearMonth, Map<String, Double>> mat = new TreeMap<>();
        for (YearMonth ym : live.keySet()) {
            mat.put(ym, readMatTableForMonth(ym));
        }

        // 4. Compare per month
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

    /** Aggregate {@code List<OpexRow>} into a Map keyed by composite dimension string. */
    private static Map<String, Double> aggregate(List<OpexRow> rows) {
        Map<String, Double> out = new HashMap<>();
        for (OpexRow r : rows) {
            String key = r.companyId() + "|" + r.costCenterId() + "|"
                    + r.expenseCategoryId() + "|" + r.isPayrollFlag();
            out.merge(key, r.opexAmountDkk(), Double::sum);
        }
        return out;
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
