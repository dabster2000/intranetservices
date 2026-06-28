package dk.trustworks.intranet.aggregates.finance.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural regression guard for the F2 audit finding.
 *
 * <p>{@code fact_project_financials_mat} is unique per
 * {@code (project_id, month_key, companyuuid)} — a project-month that spans multiple
 * legal entities has one row per company and there are no same-company duplicates.
 * When a subquery collapses a project-month to a single value WITHOUT grouping by
 * {@code companyuuid}, it must {@code SUM} the per-company {@code recognized_revenue_dkk}
 * / {@code direct_delivery_cost_dkk}. Using {@code MAX} silently keeps one company and
 * drops the rest, understating both recognized revenue and direct cost (measured against
 * prod FY25/26: revenue 100.93M → 106.97M, cost 54.75M → 62.90M once corrected).</p>
 *
 * <p>This test scans the production source rather than hitting the database because the
 * numeric difference only appears with real multi-company fact rows, which the test
 * profile does not carry. It pins the aggregation operator so the bug cannot silently
 * regress, while leaving the one benign {@code MAX} (the company-grained
 * {@code queryDirectCosts} subquery, where {@code MAX} is a no-op) intact.</p>
 */
class CxoFinanceServiceProjectFinancialsAggregationTest {

    private static final String RELATIVE_PATH =
            "src/main/java/dk/trustworks/intranet/aggregates/finance/services/CxoFinanceService.java";

    private static String source() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++) {
            Path direct = dir.resolve(RELATIVE_PATH);
            if (Files.exists(direct)) {
                return Files.readString(direct, StandardCharsets.UTF_8);
            }
            Path nested = dir.resolve("intranetservices").resolve(RELATIVE_PATH);
            if (Files.exists(nested)) {
                return Files.readString(nested, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate CxoFinanceService.java from " + Path.of("").toAbsolutePath());
    }

    private static int count(String haystack, String needle) {
        int occurrences = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            occurrences++;
        }
        return occurrences;
    }

    @Test
    void recognizedRevenueIsNeverCollapsedWithMax() throws IOException {
        String src = source();
        assertEquals(0, count(src, "MAX(f.recognized_revenue_dkk)"),
                "recognized_revenue_dkk must be SUMmed across companies per project-month, never MAX");
        assertEquals(0, count(src, "MAX(f2.recognized_revenue_dkk)"),
                "recognized_revenue_dkk must be SUMmed across companies per project-month, never MAX");
    }

    @Test
    void onlyTheCompanyGrainedSubqueryMayMaxDirectCost() throws IOException {
        String src = source();
        assertEquals(0, count(src, "MAX(f2.direct_delivery_cost_dkk)"),
                "the project-month cost collapse without companyuuid must SUM across companies, never MAX");
        // Exactly one MAX on cost may survive: the benign queryDirectCosts subquery, which
        // groups by f.companyuuid (one row per company -> MAX is a no-op, not a collapse).
        assertEquals(1, count(src, "MAX(f.direct_delivery_cost_dkk)"),
                "only the companyuuid-grained queryDirectCosts subquery may keep MAX on direct cost");
    }

    @Test
    void survivingMaxBelongsToCompanyGrainedQueryDirectCosts() throws IOException {
        String src = source();
        int methodStart = src.indexOf("private List<DirectCostRowDTO> queryDirectCosts(");
        assertTrue(methodStart >= 0, "queryDirectCosts must exist");
        int maxIdx = src.indexOf("MAX(f.direct_delivery_cost_dkk)", methodStart);
        int groupByCompany = src.indexOf("GROUP BY f.companyuuid", methodStart);
        assertTrue(maxIdx >= 0, "the benign MAX must live inside queryDirectCosts");
        assertTrue(groupByCompany > maxIdx,
                "the surviving MAX on cost must belong to a subquery that GROUPs BY f.companyuuid");
    }

    @Test
    void harmfulSubqueriesNowSumAcrossCompanies() throws IOException {
        String src = source();
        // queryActualRevenue (revenue-margin / ttm-revenue-growth path)
        assertEquals(1, count(src, "SUM(f.recognized_revenue_dkk) AS pm_revenue"),
                "queryActualRevenue must SUM recognized revenue across companies per project-month");
        // queryMonthlyMarginPercent cost dedup
        assertEquals(1, count(src, "SUM(f2.direct_delivery_cost_dkk) AS direct_delivery_cost_dkk"),
                "queryMonthlyMarginPercent must SUM direct cost across companies per project-month");
        // queryTTMRevenueAndCost + queryMonthlyRevenueAndCost
        assertEquals(2, count(src, "SUM(f.recognized_revenue_dkk) AS revenue"),
                "TTM and monthly revenue helpers must SUM recognized revenue across companies");
        // getRevenueMarginTrend cost inner + queryTTMRevenueAndCost + queryMonthlyRevenueAndCost
        assertEquals(3, count(src, "SUM(f.direct_delivery_cost_dkk) AS cost"),
                "the three project-month cost collapses must SUM direct cost across companies");
    }
}
