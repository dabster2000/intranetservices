package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.MonthData;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.ShareAmounts;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.enums.CostType;
import dk.trustworks.intranet.model.Company;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * FY-aware OPEX data provider that unifies two data sources:
 * <ol>
 *   <li><b>Current FY months</b>: computes distribution via {@link IntercompanyCalcService} so each
 *       company sees its allocated share of shared expenses.</li>
 *   <li><b>Previous FY months</b>: queries {@code fact_opex_mat} directly (raw GL), because
 *       INTERNAL_SERVICE settlement invoices have already been booked in the GL.</li>
 * </ol>
 *
 * <p>The FY boundary is calendar-based: once June 30 passes, the previous FY immediately switches
 * to raw GL. No grace period or manual flag.
 *
 * <p>Category mapping logic is intentionally kept in sync with the {@code category_mapping} CTE in
 * {@code V205__Recreate_fact_opex_with_cost_type.sql}. Any change to V205 must be reflected here
 * and vice versa. Account filtering now uses {@code cost_type IN (OPEX, SALARIES)} instead of the
 * brittle account-code-range filter [3000, 6000) that was removed in V205.
 */
@ApplicationScoped
@JBossLog
public class DistributionAwareOpexProvider {

    // -----------------------------------------------------------------------
    // Category mapping — MUST match V125__fact_opex.sql category_mapping CTE
    // -----------------------------------------------------------------------

    /**
     * Maps accounting_categories.groupname (stored as AccountingCategory.accountname in Java)
     * to the expense_category_id dimension used by fact_opex.
     *
     * Matches exactly the CASE expression in V125 category_mapping CTE.
     */
    private static final Map<String, String> GROUPNAME_TO_EXPENSE_CATEGORY;

    /**
     * Maps accounting_categories.groupname to cost_center_id dimension.
     *
     * Matches exactly the CASE expression in V125 category_mapping CTE.
     */
    private static final Map<String, String> GROUPNAME_TO_COST_CENTER;

    static {
        Map<String, String> ec = new HashMap<>();
        ec.put("Delte services",                      "PEOPLE_NON_BILLABLE");
        ec.put("Salgsfremmende omkostninger",          "SALES_MARKETING");
        ec.put("Lokaleomkostninger",                   "OFFICE_FACILITIES");
        ec.put("Variable omkostninger",                "TOOLS_SOFTWARE");
        ec.put("\u00D8vrige administrationsomk. i alt", "OTHER_OPEX");  // Øvrige administrationsomk. i alt
        GROUPNAME_TO_EXPENSE_CATEGORY = Collections.unmodifiableMap(ec);

        Map<String, String> cc = new HashMap<>();
        cc.put("Delte services",                      "HR_ADMIN");
        cc.put("Salgsfremmende omkostninger",          "SALES");
        cc.put("Lokaleomkostninger",                   "FACILITIES");
        cc.put("Variable omkostninger",                "INTERNAL_IT");
        cc.put("\u00D8vrige administrationsomk. i alt", "ADMIN");       // Øvrige administrationsomk. i alt
        GROUPNAME_TO_COST_CENTER = Collections.unmodifiableMap(cc);
    }

    /**
     * Cost types included in the OPEX distribution. Matches the filter in V205 fact_opex.
     * OPEX and SALARIES are the two types that flow through the distribution algorithm.
     * DIRECT_COSTS, REVENUE, IGNORE, and OTHER are excluded.
     */
    private static final Set<CostType> OPEX_COST_TYPES = Set.of(CostType.OPEX, CostType.SALARIES);

    /** Salary buffer multiplier — same config property used by AccountingResource. */
    @ConfigProperty(name = "dk.trustworks.intranet.aggregates.accounting.salary-buffer-multiplier", defaultValue = "1.02")
    double salaryBufferMultiplier;

    @Inject
    IntercompanyCalcService intercompanyCalcService;

    @Inject
    EntityManager em;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns OPEX rows for the given month range and optional filters.
     *
     * <p>Months in the current fiscal year are served via the distribution algorithm.
     * Months in a previous fiscal year are served from {@code fact_opex_mat} (raw GL).
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

        log.debugf("getDistributionAwareOpex: from=%s to=%s companies=%s", fromMonthKey, toMonthKey, companyIds);

        YearMonth from = parseMonthKey(fromMonthKey);
        YearMonth to   = parseMonthKey(toMonthKey);

        List<YearMonth> currentFyMonths  = new ArrayList<>();
        List<YearMonth> previousFyMonths = new ArrayList<>();

        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
            if (isCurrentFiscalYear(ym.getYear(), ym.getMonthValue())) {
                currentFyMonths.add(ym);
            } else {
                previousFyMonths.add(ym);
            }
        }

        List<OpexRow> result = new ArrayList<>();

        // Previous FY: raw GL from fact_opex_mat
        if (!previousFyMonths.isEmpty()) {
            String prevFrom = formatMonthKey(previousFyMonths.getFirst());
            String prevTo   = formatMonthKey(previousFyMonths.getLast());
            result.addAll(queryFactOpexMat(prevFrom, prevTo, companyIds, costCenters, expenseCategories));
        }

        // Current FY: distribution algorithm, batch-loaded for efficiency
        if (!currentFyMonths.isEmpty()) {
            result.addAll(computeDistributionForMonths(currentFyMonths, companyIds, costCenters, expenseCategories));
        }

        // Sort by monthKey ascending so callers get chronological order
        result.sort((a, b) -> a.monthKey().compareTo(b.monthKey()));
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

        List<OpexRow> rows = getDistributionAwareOpex(fromMonthKey, toMonthKey, companyIds, null, null);

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

        List<OpexRow> rows = getDistributionAwareOpex(fromMonthKey, toMonthKey, companyIds, null, null);

        Map<String, Double> byMonth = new TreeMap<>();
        for (OpexRow row : rows) {
            byMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
        }
        return byMonth;
    }

    /**
     * Determines whether a given month falls within the current fiscal year.
     *
     * <p>Fiscal year: July 1 (year Y) through June 30 (year Y+1).
     * Calendar-based only — once June 30 passes, the boundary moves forward immediately.
     *
     * @param year  calendar year
     * @param month calendar month (1=Jan … 12=Dec)
     * @return true if the month is within the current FY
     */
    public boolean isCurrentFiscalYear(int year, int month) {
        LocalDate today = LocalDate.now();
        int fyStartYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        LocalDate fyStart = LocalDate.of(fyStartYear, 7, 1);
        LocalDate fyEnd   = LocalDate.of(fyStartYear + 1, 6, 30);
        LocalDate monthDate = LocalDate.of(year, month, 1);
        return !monthDate.isBefore(fyStart) && !monthDate.isAfter(fyEnd);
    }

    // -----------------------------------------------------------------------
    // Previous FY: query fact_opex_mat
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<OpexRow> queryFactOpexMat(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories) {

        StringBuilder sql = new StringBuilder(
                "SELECT company_id, cost_center_id, expense_category_id, cost_type, month_key, " +
                "  SUM(opex_amount_dkk) AS opex_amount, " +
                "  SUM(invoice_count) AS invoice_count, " +
                "  MAX(is_payroll_flag) AS is_payroll " +
                "FROM fact_opex_mat " +
                "WHERE month_key >= :fromMonthKey AND month_key <= :toMonthKey "
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
            if (amount <= 0.0) continue;
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

    // -----------------------------------------------------------------------
    // Current FY: distribution computation
    // -----------------------------------------------------------------------

    /**
     * Batch-loads fiscal year data via {@link IntercompanyCalcService#loadFiscalYear} and
     * computes distribution for each requested month.
     *
     * <p>Using the batch loader avoids separate GL + availability queries per month, which
     * would be prohibitively expensive when 12 months are requested in a single page load.
     */
    private List<OpexRow> computeDistributionForMonths(
            List<YearMonth> months,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories) {

        YearMonth first = months.getFirst();
        YearMonth last  = months.getLast();

        LocalDate batchFrom = first.atDay(1);
        LocalDate batchTo   = last.plusMonths(1).atDay(1);  // exclusive

        log.debugf("Loading FY batch for distribution: %s – %s (%d months)", batchFrom, batchTo, months.size());

        IntercompanyCalcService.FiscalYearData fyData =
                intercompanyCalcService.loadFiscalYear(batchFrom, batchTo, salaryBufferMultiplier);

        List<OpexRow> result = new ArrayList<>();
        for (YearMonth ym : months) {
            MonthData md = fyData.perMonth.get(ym);
            if (md == null) {
                log.warnf("No MonthData found for %s in FiscalYearData — skipping", ym);
                continue;
            }
            Map<String, BigDecimal> lumps = fyData.lumpsByMonth.getOrDefault(ym, Collections.emptyMap());
            List<OpexRow> monthRows = computeDistributionForMonth(ym, md, lumps);
            result.addAll(applyFilters(monthRows, companyIds, costCenters, expenseCategories));
        }
        return result;
    }

    /**
     * Computes distribution-adjusted OPEX rows for a single month.
     *
     * <p>This method is cached per monthKey to avoid redundant recomputation when the same
     * month is queried by multiple CXO endpoints in a single page load.
     * The 5-minute TTL matches the {@code sp_incremental_bi_refresh} frequency.
     *
     * <p>Note: {@code @CacheResult} only caches on the method arguments as cache key.
     * The cache key here is {@code monthKey} (the first argument). Since all data for a month
     * is derived from the same GL snapshot, caching per monthKey is correct.
     *
     * <p>The method delegates computation details to
     * {@link #computeDistributionRowsForMonth(YearMonth, MonthData, Map)}
     * to keep the cached method signature simple.
     */
    @CacheResult(cacheName = "distribution-opex")
    public List<OpexRow> computeDistributionForMonth(
            @CacheKey YearMonth monthKey,
            MonthData md,
            Map<String, BigDecimal> lumpsByAccount) {
        return computeDistributionRowsForMonth(monthKey, md, lumpsByAccount);
    }

    /**
     * Core distribution logic for one month.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>For each origin company × account where {@code cost_type IN (OPEX, SALARIES)}:
     *       <ul>
     *         <li>Compute {@link ShareAmounts} via
     *             {@link IntercompanyCalcService#computeDistributionLegacyShareForAccount}</li>
     *         <li>For each payer company: allocated = baseToShare * payerRatio
     *             + (if payer==origin: originRemainder)</li>
     *       </ul>
     *   <li>Map each (origin, payer, account) to OPEX category dimensions using
     *       {@link #resolveExpenseCategory} and {@link #resolveCostCenter}.</li>
     *   <li>Aggregate per (payerCompany × costCenter × expenseCategory × month).</li>
     * </ol>
     *
     * <p>The {@code staffRemainingByCompany} map is mutated by
     * {@code computeDistributionLegacyShareForAccount} to implement salary pool capping.
     * A fresh mutable copy is created per month call to avoid cross-call contamination.
     */
    private List<OpexRow> computeDistributionRowsForMonth(
            YearMonth ym,
            MonthData md,
            Map<String, BigDecimal> lumpsByAccount) {

        String monthKeyStr = formatMonthKey(ym);

        // Salary pool cap state — must be mutable and reset per month (legacy semantics)
        Map<String, BigDecimal> staffRemainingByCompany = new HashMap<>(md.staffBaseBI102);

        // Accumulator: payerUuid → costCenterId → expenseCategoryId → {amount, isPayroll}
        Map<String, Map<String, Map<String, double[]>>> accumulator = new HashMap<>();

        for (AccountingCategory category : md.categories) {
            String groupname = category.getAccountname();  // maps to groupname column

            String expenseCategoryId = resolveExpenseCategory(groupname);
            String costCenterId      = resolveCostCenter(groupname);

            for (AccountingAccount account : category.getAccounts()) {

                // Filter by cost_type: only OPEX and SALARIES accounts participate in distribution.
                // Replaces the brittle account-code range [3000, 6000) and EXCLUDED_GROUPNAMES.
                // Matches the filter in V205 fact_opex (aa.cost_type IN ('OPEX', 'SALARIES')).
                if (!OPEX_COST_TYPES.contains(account.getCostType())) continue;

                String originUuid = account.getCompany().getUuid();

                BigDecimal gl = md.glByCompanyAccountRange
                        .getOrDefault(originUuid, Collections.emptyMap())
                        .getOrDefault(account.getAccountCode(), BigDecimal.ZERO);

                // Use absolute value to match V125 fact_opex which uses SUM(ABS(amount)).
                // Credit/reversal entries (negative GL) still contribute to OPEX totals.
                gl = gl.abs();

                BigDecimal lump = lumpsByAccount.getOrDefault(account.getUuid(), BigDecimal.ZERO);

                ShareAmounts share = intercompanyCalcService.computeDistributionLegacyShareForAccount(
                        md, account, originUuid, gl, lump, staffRemainingByCompany);

                boolean isPayroll = account.isSalary();

                for (Company payer : md.companies) {
                    String payerUuid = payer.getUuid();
                    BigDecimal ratio = md.ratioByCompany.getOrDefault(payerUuid, BigDecimal.ZERO);

                    BigDecimal allocated = BigDecimal.ZERO;

                    if (share.baseToShare.compareTo(BigDecimal.ZERO) > 0) {
                        allocated = allocated.add(
                                share.baseToShare.multiply(ratio).setScale(IntercompanyCalcService.SCALE, IntercompanyCalcService.RM)
                        );
                    }
                    if (payerUuid.equals(originUuid) && share.originRemainder.compareTo(BigDecimal.ZERO) > 0) {
                        allocated = allocated.add(share.originRemainder);
                    }

                    if (allocated.compareTo(BigDecimal.ZERO) <= 0) continue;

                    // Accumulate: payerUuid → costCenterId → expenseCategoryId → [amount, payrollFlag]
                    accumulator
                            .computeIfAbsent(payerUuid, k -> new HashMap<>())
                            .computeIfAbsent(costCenterId, k -> new HashMap<>())
                            .merge(
                                    expenseCategoryId,
                                    new double[]{allocated.doubleValue(), isPayroll ? 1.0 : 0.0},
                                    (existing, incoming) -> new double[]{
                                            existing[0] + incoming[0],
                                            Math.max(existing[1], incoming[1])
                                    }
                            );
                }
            }
        }

        // Flatten accumulator → OpexRow list
        List<OpexRow> rows = new ArrayList<>();
        for (var payerEntry : accumulator.entrySet()) {
            String payerUuid = payerEntry.getKey();
            for (var ccEntry : payerEntry.getValue().entrySet()) {
                String costCenterId = ccEntry.getKey();
                for (var catEntry : ccEntry.getValue().entrySet()) {
                    String expenseCategoryId = catEntry.getKey();
                    double[] vals = catEntry.getValue();
                    double amount = vals[0];
                    boolean isPayroll = vals[1] > 0.0;

                    if (amount <= 0.0) continue;

                    rows.add(new OpexRow(
                            payerUuid,
                            costCenterId,
                            expenseCategoryId,
                            monthKeyStr,
                            amount,
                            1,  // distribution rows don't have individual GL entry counts
                            isPayroll,
                            OpexRow.SOURCE_DISTRIBUTION
                    ));
                }
            }
        }

        log.debugf("Distribution computed for %s: %d rows across %d companies",
                monthKeyStr, rows.size(), md.companies.size());
        return rows;
    }

    // -----------------------------------------------------------------------
    // Category mapping helpers
    // -----------------------------------------------------------------------

    /**
     * Maps the category groupname (DB: accounting_categories.groupname,
     * Java: AccountingCategory.accountname) to expense_category_id.
     *
     * <p>Defaults to "OTHER_OPEX" for unknown groupnames, matching V125 ELSE clause.
     */
    private static String resolveExpenseCategory(String groupname) {
        return GROUPNAME_TO_EXPENSE_CATEGORY.getOrDefault(groupname, "OTHER_OPEX");
    }

    /**
     * Maps the category groupname to cost_center_id.
     *
     * <p>Defaults to "GENERAL" for unknown groupnames, matching V125 ELSE clause.
     */
    private static String resolveCostCenter(String groupname) {
        return GROUPNAME_TO_COST_CENTER.getOrDefault(groupname, "GENERAL");
    }

    // -----------------------------------------------------------------------
    // Post-fetch filtering
    // -----------------------------------------------------------------------

    /**
     * Applies optional dimension filters to a list of OpexRow values.
     *
     * <p>Filtering is done in Java rather than in SQL for the distribution path,
     * because distribution computes all companies and categories in one pass.
     */
    private static List<OpexRow> applyFilters(
            List<OpexRow> rows,
            Set<String> companyIds,
            Set<String> costCenters,
            Set<String> expenseCategories) {

        if ((companyIds == null || companyIds.isEmpty())
                && (costCenters == null || costCenters.isEmpty())
                && (expenseCategories == null || expenseCategories.isEmpty())) {
            return rows;
        }

        List<OpexRow> filtered = new ArrayList<>(rows.size());
        for (OpexRow row : rows) {
            if (companyIds != null && !companyIds.isEmpty() && !companyIds.contains(row.companyId())) continue;
            if (costCenters != null && !costCenters.isEmpty() && !costCenters.contains(row.costCenterId())) continue;
            if (expenseCategories != null && !expenseCategories.isEmpty() && !expenseCategories.contains(row.expenseCategoryId())) continue;
            filtered.add(row);
        }
        return filtered;
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
