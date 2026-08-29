package dk.trustworks.intranet.aggregates.delivery.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the Realization Rate (TTM) KPI query seams in
 * {@link CxoDeliveryService}.
 *
 * <p>These are plain unit tests (no {@code @QuarkusTest}) so they run in the fast tier that
 * gates deploys.
 *
 * <p>The KPI previously shipped two defects that these tests pin down:
 * <ol>
 *   <li>The expected-value query filtered on {@code w.billable = true}. Nothing has written
 *       {@code work.billable} since 2023-12-08, so the row set was empty and the KPI reported
 *       a flat 0.0% for every recent period.</li>
 *   <li>Numerator and denominator were both {@code SUM(workduration * rate)} over the same
 *       row set — algebraically identical — so even with the filter removed the KPI could
 *       only return a constant 100%.</li>
 * </ol>
 */
class CxoDeliveryServiceRealizationRateTest {

    @Nested
    @DisplayName("expected-value SQL (work_full_optimized)")
    class ExpectedValueSql {

        @Test
        @DisplayName("never filters on the dead work.billable flag")
        void neverFiltersOnBillableFlag() {
            for (boolean practices : new boolean[]{false, true}) {
                for (boolean companies : new boolean[]{false, true}) {
                    String sql = CxoDeliveryService.buildExpectedValueSql(practices, companies);
                    assertFalse(sql.contains("billable"),
                            "work.billable has not been maintained since 2023-12-08 and must "
                                    + "never gate the realization KPI; SQL was: " + sql);
                }
            }
        }

        @Test
        @DisplayName("uses rate > 0 as the billability signal")
        void usesRateAsBillabilitySignal() {
            String sql = CxoDeliveryService.buildExpectedValueSql(false, false);
            assertTrue(sql.contains("w.rate > 0"), sql);
            assertTrue(sql.contains("w.workduration > 0"), sql);
            assertTrue(sql.contains("SUM(w.workduration * w.rate)"), sql);
            assertTrue(sql.contains("FROM work_full_optimized w"), sql);
            assertTrue(sql.contains("w.type = 'CONSULTANT'"), sql);
        }

        @Test
        @DisplayName("excludes internal Trustworks work, which is never invoiced")
        void excludesInternalClients() {
            String sql = CxoDeliveryService.buildExpectedValueSql(false, false);
            assertTrue(sql.contains(":excludedClientIds"), sql);
        }

        @Test
        @DisplayName("applies the practice filter — it used to be accepted and ignored")
        void appliesPracticeFilter() {
            String without = CxoDeliveryService.buildExpectedValueSql(false, false);
            String with = CxoDeliveryService.buildExpectedValueSql(true, false);

            assertFalse(without.contains(":practices"), without);
            assertTrue(with.contains(":practices"), with);
            // Practice comes from the consultant's practice registry, not a column on the view.
            assertTrue(with.contains("u.practice_uuid"), with);
        }

        @Test
        @DisplayName("applies the company filter only when companies are supplied")
        void appliesCompanyFilter() {
            assertFalse(CxoDeliveryService.buildExpectedValueSql(false, false).contains(":companyIds"));
            assertTrue(CxoDeliveryService.buildExpectedValueSql(false, true)
                    .contains("w.consultant_company_uuid IN (:companyIds)"));
        }
    }

    @Nested
    @DisplayName("actual-revenue SQL (fact_project_financials_mat)")
    class ActualRevenueSql {

        @Test
        @DisplayName("reads invoiced revenue, not the timesheet")
        void readsInvoicedRevenue() {
            String sql = CxoDeliveryService.buildActualRevenueSql(false, false);
            assertTrue(sql.contains("FROM fact_project_financials_mat f"), sql);
            assertTrue(sql.contains("recognized_revenue_dkk"), sql);
            assertFalse(sql.contains("work_full_optimized"),
                    "numerator and denominator must come from independent sources, "
                            + "otherwise the ratio is a constant; SQL was: " + sql);
        }

        @Test
        @DisplayName("deduplicates per project-month with SUM, not MAX")
        void deduplicatesPerProjectMonth() {
            String sql = CxoDeliveryService.buildActualRevenueSql(false, false);
            assertTrue(sql.contains("GROUP BY f.project_id, f.month_key"), sql);
            // V118 grain is (project_id, month_key, companyuuid). MAX would silently drop
            // the other companies' revenue on a project-month split across companies.
            assertTrue(sql.contains("SUM(f.recognized_revenue_dkk)"), sql);
            assertFalse(sql.contains("MAX(f.recognized_revenue_dkk)"), sql);
        }

        @Test
        @DisplayName("excludes internal Trustworks work on the actual side too")
        void excludesInternalClients() {
            String sql = CxoDeliveryService.buildActualRevenueSql(false, false);
            assertTrue(sql.contains("f.client_id IS NOT NULL"), sql);
            assertTrue(sql.contains("f.client_id NOT IN (:excludedClientIds)"), sql);
        }

        @Test
        @DisplayName("maps practices onto service_line_id, the canonical code space after V429")
        void mapsPracticesToServiceLine() {
            assertFalse(CxoDeliveryService.buildActualRevenueSql(false, false).contains(":practices"));
            assertTrue(CxoDeliveryService.buildActualRevenueSql(true, false)
                    .contains("f.service_line_id IN (:practices)"));
        }

        @Test
        @DisplayName("applies the company filter only when companies are supplied")
        void appliesCompanyFilter() {
            assertFalse(CxoDeliveryService.buildActualRevenueSql(false, false).contains(":companyIds"));
            assertTrue(CxoDeliveryService.buildActualRevenueSql(false, true)
                    .contains("f.companyuuid IN (:companyIds)"));
        }
    }

    @Nested
    @DisplayName("realization arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("billed over expected, as a percentage")
        void computesPercentage() {
            assertEquals(80.9, CxoDeliveryService.realizationPercent(108_427_716d, 133_971_615d), 0.05);
            assertEquals(50.0, CxoDeliveryService.realizationPercent(50d, 100d), 1e-9);
        }

        @Test
        @DisplayName("a rate above 100% is reported, not clamped — it signals over-recovery")
        void doesNotClampAbove100() {
            assertEquals(110.0, CxoDeliveryService.realizationPercent(110d, 100d), 1e-9);
        }

        @Test
        @DisplayName("no expected value yields 0, never a divide-by-zero")
        void guardsZeroExpected() {
            assertEquals(0.0, CxoDeliveryService.realizationPercent(1234d, 0d), 1e-9);
            assertEquals(0.0, CxoDeliveryService.realizationPercent(1234d, -1d), 1e-9);
        }

        @Test
        @DisplayName("zero billed against real expected value is a real 0%, not a missing metric")
        void zeroBilledIsZeroPercent() {
            assertEquals(0.0, CxoDeliveryService.realizationPercent(0d, 100d), 1e-9);
        }
    }
}
