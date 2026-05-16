package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.FiscalYearData;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.MonthData;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService.ShareAmounts;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.FiscalYearRange;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.enums.CostType;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refreshes {@code fact_opex_distribution_mat} by running the existing
 * {@link IntercompanyCalcService#loadFiscalYear} + distribution algorithm
 * once per night and writing the resulting {@link OpexRow}s into the table.
 *
 * <p>The provider that powers the CXO EBITDA forecast endpoint reads from this
 * table for unsettled months instead of recomputing on the request path.
 *
 * <p>The distribution algorithm itself lives here (moved from
 * {@link DistributionAwareOpexProvider} in PR 2) so the hot read path and the
 * nightly write path are no longer coupled: the provider does pure SQL reads
 * and this service is the only place that runs the distribution math.
 *
 * <p>Category mapping logic is intentionally kept in sync with the
 * {@code category_mapping} CTE in {@code V205__Recreate_fact_opex_with_cost_type.sql}.
 * Any change to V205 must be reflected here and vice versa. Account filtering uses
 * {@code cost_type IN (OPEX, SALARIES)} instead of the brittle account-code-range
 * filter [3000, 6000) that was removed in V205.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md
 */
@ApplicationScoped
@JBossLog
public class OpexDistributionRefreshService {

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
        ec.put("Øvrige administrationsomk. i alt", "OTHER_OPEX");  // Øvrige administrationsomk. i alt
        GROUPNAME_TO_EXPENSE_CATEGORY = Collections.unmodifiableMap(ec);

        Map<String, String> cc = new HashMap<>();
        cc.put("Delte services",                      "HR_ADMIN");
        cc.put("Salgsfremmende omkostninger",          "SALES");
        cc.put("Lokaleomkostninger",                   "FACILITIES");
        cc.put("Variable omkostninger",                "INTERNAL_IT");
        cc.put("Øvrige administrationsomk. i alt", "ADMIN");       // Øvrige administrationsomk. i alt
        GROUPNAME_TO_COST_CENTER = Collections.unmodifiableMap(cc);
    }

    /**
     * Cost types included in the OPEX distribution. Matches the filter in V205 fact_opex.
     * OPEX and SALARIES are the two types that flow through the distribution algorithm.
     * DIRECT_COSTS, REVENUE, IGNORE, and OTHER are excluded.
     */
    private static final Set<CostType> OPEX_COST_TYPES = Set.of(CostType.OPEX, CostType.SALARIES);

    @Inject
    IntercompanyCalcService intercompanyCalcService;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "dk.trustworks.intranet.opex-distribution.refresh-window-fy-back", defaultValue = "1")
    int fyBack;

    @ConfigProperty(name = "dk.trustworks.intranet.aggregates.accounting.salary-buffer-multiplier", defaultValue = "1.02")
    double salaryBufferMultiplier;

    public record RefreshOutcome(int inserted, int deleted, Duration took,
                                 LocalDate windowFrom, LocalDate windowTo) {}

    /**
     * Rebuild all rows in the window [currentFY - fyBack, currentFY + 1).
     * Idempotent — safe to call any number of times.
     */
    @Transactional
    public RefreshOutcome refresh() {
        Instant start = Instant.now();

        FiscalYearRange currentFy =
                UtilizationCalculationHelper.getCurrentFiscalYearRange();
        LocalDate windowFrom = currentFy.start().minusYears(fyBack);
        LocalDate windowToExclusive = currentFy.end().plusDays(1);

        FiscalYearData fyData = intercompanyCalcService.loadFiscalYear(
                windowFrom, windowToExclusive, salaryBufferMultiplier);

        List<YearMonth> allMonths = new ArrayList<>(fyData.perMonth.keySet());
        List<OpexRow> allRows = computeDistributionForMonths(allMonths, fyData);

        String fromKey = UtilizationCalculationHelper.toMonthKey(windowFrom);
        String toKey = UtilizationCalculationHelper.toMonthKey(windowToExclusive);

        int deleted = em.createNativeQuery(
                "DELETE FROM fact_opex_distribution_mat " +
                "WHERE month_key >= :fromKey AND month_key < :toKey")
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .executeUpdate();

        int inserted = bulkInsert(allRows, LocalDateTime.now());

        Duration took = Duration.between(start, Instant.now());
        log.infof("Refreshed fact_opex_distribution_mat: deleted=%d inserted=%d took=%dms window=[%s..%s)",
                deleted, inserted, took.toMillis(), windowFrom, windowToExclusive);

        return new RefreshOutcome(inserted, deleted, took, windowFrom, windowToExclusive);
    }

    // -----------------------------------------------------------------------
    // Distribution algorithm — sole owner of the math. Package-private so the
    // parity test in this same package can verify the algorithm's output
    // matches what we materialize.
    // -----------------------------------------------------------------------

    /**
     * Batch-loads fiscal year data via {@link IntercompanyCalcService#loadFiscalYear} and
     * computes distribution for each requested month. The {@code fyData} parameter is
     * required pre-loaded by the caller — that lets {@link #refresh()} share one
     * batch load with the algorithm.
     *
     * <p>No filters are applied here — the refresh always materializes every row.
     * Filtering happens on the read path inside {@link DistributionAwareOpexProvider}.
     */
    List<OpexRow> computeDistributionForMonths(
            List<YearMonth> months,
            FiscalYearData fyData) {
        List<OpexRow> result = new ArrayList<>();
        for (YearMonth ym : months) {
            IntercompanyCalcService.MonthData md = fyData.perMonth.get(ym);
            if (md == null) {
                log.warnf("No MonthData found for %s in FiscalYearData — skipping", ym);
                continue;
            }
            Map<String, BigDecimal> lumps = fyData.lumpsByMonth
                    .getOrDefault(ym, Collections.emptyMap());
            result.addAll(computeDistributionRowsForMonth(ym, md, lumps));
        }
        return result;
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

        // Accumulator: payerUuid → costCenterId → expenseCategoryId → isPayroll → amount.
        // isPayroll is a key dimension so SALARIES accounts cannot contaminate the OPEX
        // total inside categories that mix both cost types (e.g. "Delte services" maps
        // 84 OPEX accounts and 3 SALARIES accounts into PEOPLE_NON_BILLABLE).
        Map<String, Map<String, Map<String, Map<Boolean, Double>>>> accumulator = new HashMap<>();

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

                // Use signed GL aggregate; refunds/reversals correctly net against costs.

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
                    if (payerUuid.equals(originUuid) && share.originRemainder.compareTo(BigDecimal.ZERO) != 0) {
                        allocated = allocated.add(share.originRemainder);
                    }

                    if (allocated.compareTo(BigDecimal.ZERO) == 0) continue;

                    // Accumulate: payerUuid → costCenterId → expenseCategoryId → isPayroll → amount
                    accumulator
                            .computeIfAbsent(payerUuid, k -> new HashMap<>())
                            .computeIfAbsent(costCenterId, k -> new HashMap<>())
                            .computeIfAbsent(expenseCategoryId, k -> new HashMap<>())
                            .merge(isPayroll, allocated.doubleValue(), Double::sum);
                }
            }
        }

        // Flatten accumulator → OpexRow list. Each (payer, costCenter, expenseCategory)
        // can produce up to two rows — one with isPayroll=true and one with isPayroll=false —
        // mirroring how fact_opex_mat keeps SALARIES and OPEX rows separate by cost_type.
        List<OpexRow> rows = new ArrayList<>();
        for (var payerEntry : accumulator.entrySet()) {
            String payerUuid = payerEntry.getKey();
            for (var ccEntry : payerEntry.getValue().entrySet()) {
                String costCenterId = ccEntry.getKey();
                for (var catEntry : ccEntry.getValue().entrySet()) {
                    String expenseCategoryId = catEntry.getKey();
                    for (var prEntry : catEntry.getValue().entrySet()) {
                        boolean isPayroll = prEntry.getKey();
                        double amount = prEntry.getValue();

                        if (amount == 0.0) continue;

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
        }

        log.debugf("Distribution computed for %s: %d rows across %d companies",
                monthKeyStr, rows.size(), md.companies.size());
        return rows;
    }

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

    private static String formatMonthKey(YearMonth ym) {
        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    // -----------------------------------------------------------------------
    // Bulk insert
    // -----------------------------------------------------------------------

    private int bulkInsert(List<OpexRow> rows, LocalDateTime refreshedAt) {
        if (rows.isEmpty()) return 0;

        StringBuilder sql = new StringBuilder(
                "INSERT INTO fact_opex_distribution_mat " +
                "(opex_distribution_id, company_id, cost_center_id, expense_category_id, " +
                " month_key, year, month_number, fiscal_year, fiscal_month_number, " +
                " fiscal_month_key, cost_type, opex_amount_dkk, is_payroll_flag, " +
                " invoice_count, data_source, refreshed_at) VALUES ");

        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(:id").append(i).append(", :company").append(i)
               .append(", :cc").append(i).append(", :cat").append(i)
               .append(", :mk").append(i).append(", :yr").append(i)
               .append(", :mn").append(i).append(", :fy").append(i)
               .append(", :fmn").append(i).append(", :fmk").append(i)
               .append(", :ct").append(i).append(", :amt").append(i)
               .append(", :pf").append(i).append(", :ic").append(i)
               .append(", :ds").append(i).append(", :ra").append(i).append(")");
        }
        // Idempotency safety: surrogate key collisions are impossible inside one
        // refresh (we DELETE the window first), but ON DUPLICATE KEY UPDATE
        // protects against accidental concurrent runs.
        sql.append(" ON DUPLICATE KEY UPDATE " +
                "  opex_amount_dkk = VALUES(opex_amount_dkk), " +
                "  invoice_count   = VALUES(invoice_count), " +
                "  refreshed_at    = VALUES(refreshed_at)");

        Query q = em.createNativeQuery(sql.toString());
        for (int i = 0; i < rows.size(); i++) {
            OpexRow r = rows.get(i);
            YearMonth ym = YearMonth.parse(r.monthKey(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            int monthVal = ym.getMonthValue();
            int yearVal = ym.getYear();
            int fyVal = DateUtils.fiscalYearStart(ym.atDay(1)).getYear();
            int fmn = monthVal >= 7 ? monthVal - 6 : monthVal + 6;
            String fmk = String.format("FY%04d-%02d", fyVal, fmn);
            String cost = r.isPayrollFlag() ? OpexRow.COST_TYPE_SALARIES : OpexRow.COST_TYPE_OPEX;
            String id = r.companyId() + "-" + r.costCenterId() + "-"
                      + r.expenseCategoryId() + "-" + (r.isPayrollFlag() ? "1" : "0")
                      + "-" + r.monthKey();

            q.setParameter("id" + i, id)
             .setParameter("company" + i, r.companyId())
             .setParameter("cc" + i, r.costCenterId())
             .setParameter("cat" + i, r.expenseCategoryId())
             .setParameter("mk" + i, r.monthKey())
             .setParameter("yr" + i, yearVal)
             .setParameter("mn" + i, monthVal)
             .setParameter("fy" + i, fyVal)
             .setParameter("fmn" + i, fmn)
             .setParameter("fmk" + i, fmk)
             .setParameter("ct" + i, cost)
             .setParameter("amt" + i, r.opexAmountDkk())
             .setParameter("pf" + i, r.isPayrollFlag() ? 1 : 0)
             .setParameter("ic" + i, r.invoiceCount())
             .setParameter("ds" + i, OpexRow.SOURCE_DISTRIBUTION)
             .setParameter("ra" + i, refreshedAt);
        }
        return q.executeUpdate();
    }
}
