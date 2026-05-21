package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Settlement-aware OPEX data provider that unifies two data sources per month:
 * <ol>
 *   <li><b>Unsettled months</b>: months for which no finalized INTERNAL_SERVICE invoice exists
 *       — {@code fact_opex_mat} still reflects raw pre-distribution GL, so this provider reads
 *       from {@code fact_opex_distribution_mat} (populated nightly by
 *       {@link OpexDistributionRefreshService}) where each company sees its allocated share of
 *       shared expenses.</li>
 *   <li><b>Settled months</b>: months with at least one finalized INTERNAL_SERVICE invoice
 *       (status != DRAFT) — settlement is already booked in the GL via the resulting voucher,
 *       so this provider queries {@code fact_opex_mat} directly (raw GL).</li>
 * </ol>
 *
 * <p>Settlement is detected per (year, month) on the {@code invoices} table. Any single
 * finalized INTERNAL_SERVICE invoice for that month flips the month to the raw-GL path —
 * partial-coverage cases (some sender→debtor pairs invoiced, others not) are rare in practice
 * and treated as fully settled.
 *
 * <p>This replaces an earlier calendar-based switch that assumed settlement always happened at
 * fiscal-year close. In reality INTERNAL_SERVICE invoices are issued with arbitrary delay —
 * sometimes monthly, sometimes lumped at year-end, sometimes skipped entirely (e.g. due to cash
 * position). The settlement-presence check makes the chart correct regardless of cadence.
 *
 * <p>Prior to PR 2 this provider also computed the distribution algorithm inline on every
 * request, which made the EBITDA forecast endpoint a >30s cold-path. The algorithm now lives in
 * {@link OpexDistributionRefreshService} and runs nightly; this class reads the materialized
 * output and stays on the hot read path. Category mapping logic and {@code OPEX_COST_TYPES} have
 * therefore moved to the refresh service alongside the compute methods.
 */
@ApplicationScoped
@JBossLog
public class DistributionAwareOpexProvider {

    @Inject
    EntityManager em;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns OPEX rows for the given month range and optional filters.
     *
     * <p>Months with a finalized INTERNAL_SERVICE invoice are served from
     * {@code fact_opex_mat} (raw GL). All other months are served from
     * {@code fact_opex_distribution_mat} which the nightly refresh batch populates.
     *
     * @param fromMonthKey     start month inclusive, format YYYYMM
     * @param toMonthKey       end month inclusive, format YYYYMM
     * @param companyIds       company UUID filter (null/empty = all companies)
     * @param costCenters      cost center filter (null/empty = all)
     * @param expenseCategories expense category filter (null/empty = all)
     * @return unified list of OpexRow, sorted by monthKey ascending
     */
    public List<OpexRow> getDistributionAwareOpex(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories) {
        return getDistributionAwareOpex(fromMonthKey, toMonthKey, companyIds, costCenters, expenseCategories, CostSource.BOOKED);
    }

    public List<OpexRow> getDistributionAwareOpex(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories,
            CostSource costSource) {

        CostSource source = costSource == null ? CostSource.BOOKED : costSource;

        log.debugf("getDistributionAwareOpex: from=%s to=%s companies=%s costSource=%s",
                fromMonthKey, toMonthKey, companyIds, source);

        YearMonth from = parseMonthKey(fromMonthKey);
        YearMonth to   = parseMonthKey(toMonthKey);

        Set<YearMonth> settledMonths = findSettledMonths(from, to);

        // Build contiguous-range monthKey strings for the two paths. The settled
        // path queries fact_opex_mat by [min, max] range; the unsettled path queries
        // fact_opex_distribution_mat the same way. We use min..max (not a list) because:
        //   - it preserves the IN-clause-free SQL shape we already have for fact_opex_mat
        //   - filter by `WHERE month_key IN settledKeys` inline after the fetch handles
        //     the rare non-contiguous case
        List<String> settledKeys   = new ArrayList<>();
        List<String> unsettledKeys = new ArrayList<>();
        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
            (settledMonths.contains(ym) ? settledKeys : unsettledKeys)
                    .add(formatMonthKey(ym));
        }

        List<OpexRow> result = new ArrayList<>();
        if (!settledKeys.isEmpty()) {
            String prevFrom = settledKeys.getFirst();
            String prevTo   = settledKeys.getLast();
            List<OpexRow> rawRows = queryFactOpexMat(prevFrom, prevTo,
                    companyIds, costCenters, expenseCategories, source);
            // Keep only settled months (covers the non-contiguous case).
            for (OpexRow row : rawRows) {
                if (settledMonths.contains(parseMonthKey(row.monthKey()))) {
                    result.add(row);
                }
            }
        }
        if (!unsettledKeys.isEmpty()) {
            String distFrom = unsettledKeys.getFirst();
            String distTo   = unsettledKeys.getLast();
            List<OpexRow> distRows = queryFactOpexDistributionMat(distFrom, distTo,
                    companyIds, costCenters, expenseCategories, source);
            // Same non-contiguous filter, in case a settled month sits between
            // two unsettled ones.
            Set<YearMonth> unsettledSet = new HashSet<>();
            for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
                if (!settledMonths.contains(ym)) unsettledSet.add(ym);
            }
            for (OpexRow row : distRows) {
                if (unsettledSet.contains(parseMonthKey(row.monthKey()))) {
                    result.add(row);
                }
            }
        }

        result.sort(java.util.Comparator.comparing(OpexRow::monthKey));
        return result;
    }

    /**
     * Returns total OPEX and distinct month count over the given range.
     *
     * <p>Used by EBITDA TTM average calculation:
     * {@code avgMonthlyOpex = totalOpex / distinctMonths}.
     *
     * @param fromMonthKey start month inclusive, YYYYMM
     * @param toMonthKey   end month inclusive, YYYYMM
     * @param companyIds   optional company filter
     * @return double[]{totalOpex, distinctMonthCount}
     */
    public double[] getOpexTotalAndMonthCount(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds) {

        List<OpexRow> rows = getDistributionAwareOpex(fromMonthKey, toMonthKey, companyIds, null, null, CostSource.BOOKED);
        return totalAndMonthCount(rows);
    }

    public double[] getOpexTotalAndMonthCount(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            CostSource costSource) {

        List<OpexRow> rows = getDistributionAwareOpex(fromMonthKey, toMonthKey, companyIds, null, null, costSource);
        return totalAndMonthCount(rows);
    }

    private double[] totalAndMonthCount(List<OpexRow> rows) {
        double total = rows.stream().mapToDouble(OpexRow::opexAmountDkk).sum();
        long distinctMonths = rows.stream().map(OpexRow::monthKey).distinct().count();
        return new double[]{total, (double) distinctMonths};
    }

    /**
     * Returns monthly OPEX totals aggregated across all companies and categories.
     *
     * <p>Used by EBITDA monthly chart — returns one total per month key.
     *
     * @param fromMonthKey start month inclusive, YYYYMM
     * @param toMonthKey   end month inclusive, YYYYMM
     * @param companyIds   optional company filter
     * @return map of monthKey → total opex_amount_dkk
     */
    public Map<String, Double> getMonthlyOpex(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds) {

        return getMonthlyOpex(fromMonthKey, toMonthKey, companyIds, CostSource.BOOKED);
    }

    public Map<String, Double> getMonthlyOpex(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            CostSource costSource) {

        List<OpexRow> rows = getDistributionAwareOpex(fromMonthKey, toMonthKey, companyIds, null, null, costSource);

        Map<String, Double> byMonth = new TreeMap<>();
        for (OpexRow row : rows) {
            byMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
        }
        return byMonth;
    }

    /**
     * Returns the subset of {@code [from, to]} months that have at least one finalized
     * INTERNAL_SERVICE invoice in the {@code invoices} table.
     *
     * <p>"Finalized" means {@code status != 'DRAFT'} — DRAFT invoices are work-in-progress
     * and have not yet produced a GL voucher in e-conomic, so the GL still reflects raw
     * pre-distribution amounts. Once a DRAFT is finalized (CREATED / QUEUED / PENDING_REVIEW),
     * the corresponding voucher is booked and the GL becomes the settlement truth for that month.
     *
     * <p>Granularity is per (year, month). Any single finalized INTERNAL_SERVICE for the month
     * counts as full settlement; partial-coverage cases are treated as settled. The
     * {@code year}/{@code month} columns on {@code invoices} represent the cost period the
     * invoice covers, not its issue date.
     */
    @SuppressWarnings("unchecked")
    public Set<YearMonth> findSettledMonths(YearMonth from, YearMonth to) {
        int fromKey = from.getYear() * 100 + from.getMonthValue();
        int toKey   = to.getYear()   * 100 + to.getMonthValue();

        String sql = "SELECT DISTINCT year, month FROM invoices " +
                     "WHERE type = 'INTERNAL_SERVICE' " +
                     "  AND status <> 'DRAFT' " +
                     "  AND (year * 100 + month) >= :fromKey " +
                     "  AND (year * 100 + month) <= :toKey";

        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getResultList();

        Set<YearMonth> result = new HashSet<>();
        for (Object[] row : rows) {
            int yr = ((Number) row[0]).intValue();
            int mn = ((Number) row[1]).intValue();
            result.add(YearMonth.of(yr, mn));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Settled months: query fact_opex_mat
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<OpexRow> queryFactOpexMat(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories,
            CostSource costSource) {

        StringBuilder sql = new StringBuilder(
                "SELECT company_id, cost_center_id, expense_category_id, cost_type, month_key, " +
                "  SUM(opex_amount_dkk) AS opex_amount, " +
                "  SUM(invoice_count) AS invoice_count, " +
                "  MAX(is_payroll_flag) AS is_payroll " +
                "FROM fact_opex_mat " +
                "WHERE month_key >= :fromMonthKey AND month_key <= :toMonthKey " +
                "AND posting_status IN (:postingStatuses) "
        );

        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND company_id IN (:companyIds) ");
        }
        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("AND cost_center_id IN (:costCenters) ");
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            sql.append("AND expense_category_id IN (:expenseCategories) ");
        }
        sql.append("GROUP BY company_id, cost_center_id, expense_category_id, cost_type, month_key " +
                   "ORDER BY month_key ASC");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);
        query.setParameter("postingStatuses", costSource.postingStatusNames());
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (costCenters != null && !costCenters.isEmpty()) {
            query.setParameter("costCenters", costCenters);
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            query.setParameter("expenseCategories", expenseCategories);
        }

        List<Tuple> rows = query.getResultList();
        List<OpexRow> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            double amount = row.get("opex_amount") != null ? ((Number) row.get("opex_amount")).doubleValue() : 0.0;
            if (amount == 0.0) continue;
            result.add(new OpexRow(
                    (String) row.get("company_id"),
                    (String) row.get("cost_center_id"),
                    (String) row.get("expense_category_id"),
                    (String) row.get("month_key"),
                    amount,
                    row.get("invoice_count") != null ? ((Number) row.get("invoice_count")).intValue() : 0,
                    "SALARIES".equals(row.get("cost_type")),
                    OpexRow.SOURCE_ERP_GL
            ));
        }
        return result;
    }

    /**
     * Reads OPEX rows for the given months from {@code fact_opex_distribution_mat}.
     * Mirror of {@link #queryFactOpexMat}, but for distribution-computed rows that
     * the nightly batchlet writes. Result rows carry {@code OpexRow.SOURCE_DISTRIBUTION}.
     *
     * <p>Callers (in particular {@link #getDistributionAwareOpex}) decide which
     * months go to which table based on settlement state.
     */
    @SuppressWarnings("unchecked")
    private List<OpexRow> queryFactOpexDistributionMat(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories,
            CostSource costSource) {

        StringBuilder sql = new StringBuilder(
                "SELECT company_id, cost_center_id, expense_category_id, cost_type, month_key, " +
                "  SUM(opex_amount_dkk) AS opex_amount, " +
                "  SUM(invoice_count) AS invoice_count, " +
                "  MAX(is_payroll_flag) AS is_payroll " +
                "FROM fact_opex_distribution_mat " +
                "WHERE month_key >= :fromMonthKey AND month_key <= :toMonthKey " +
                "AND posting_status IN (:postingStatuses) "
        );

        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND company_id IN (:companyIds) ");
        }
        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("AND cost_center_id IN (:costCenters) ");
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            sql.append("AND expense_category_id IN (:expenseCategories) ");
        }
        sql.append("GROUP BY company_id, cost_center_id, expense_category_id, cost_type, month_key " +
                   "ORDER BY month_key ASC");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);
        query.setParameter("postingStatuses", costSource.postingStatusNames());
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (costCenters != null && !costCenters.isEmpty()) {
            query.setParameter("costCenters", costCenters);
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            query.setParameter("expenseCategories", expenseCategories);
        }

        List<Tuple> rows = query.getResultList();
        List<OpexRow> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            double amount = row.get("opex_amount") != null
                    ? ((Number) row.get("opex_amount")).doubleValue() : 0.0;
            if (amount == 0.0) continue;
            result.add(new OpexRow(
                    (String) row.get("company_id"),
                    (String) row.get("cost_center_id"),
                    (String) row.get("expense_category_id"),
                    (String) row.get("month_key"),
                    amount,
                    row.get("invoice_count") != null
                            ? ((Number) row.get("invoice_count")).intValue() : 0,
                    "SALARIES".equals(row.get("cost_type")),
                    OpexRow.SOURCE_DISTRIBUTION
            ));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Month key utilities
    // -----------------------------------------------------------------------

    private static YearMonth parseMonthKey(String monthKey) {
        if (monthKey == null || monthKey.length() != 6) {
            throw new IllegalArgumentException("Invalid monthKey (expected YYYYMM): " + monthKey);
        }
        int year  = Integer.parseInt(monthKey.substring(0, 4));
        int month = Integer.parseInt(monthKey.substring(4, 6));
        return YearMonth.of(year, month);
    }

    private static String formatMonthKey(YearMonth ym) {
        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }
}
