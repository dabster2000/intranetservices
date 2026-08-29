package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthBaselineDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthTimelineDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthTimelineMonthDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import dk.trustworks.intranet.financeservice.services.BankLiquidityService;
import dk.trustworks.intranet.financeservice.services.BankLiquidityService.GroupFlowMonth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toDouble;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toLong;

/**
 * Data access + assembly for the executive dashboard's Growth &amp; Scenarios tab:
 * the multi-year growth timeline (revenue, cost, people) and the measured
 * baseline that seeds the client-side scenario simulation.
 *
 * <p>Sources (all existing canonical facts — nothing new is materialized):</p>
 * <ul>
 *   <li>Revenue: {@code fact_company_revenue_mat.net_revenue_dkk} (invoice-based,
 *       from 2017-07). Same source as {@link RevenueAnalyticsProvider}.</li>
 *   <li>Cost: {@link DistributionAwareOpexProvider#getMonthlyOpex} (OPEX incl.
 *       payroll, shared-services allocation applied) plus GL DIRECT_COSTS from
 *       {@code finance_details} — the exact same two components the
 *       revenue-cost-forecast endpoint sums, so the timeline's implied EBITDA
 *       reconciles with the Executive Summary chart. Cost facts exist from
 *       2024-07 ({@link #COST_DATA_FROM_KEY}); earlier months carry null.</li>
 *   <li>People: {@code userstatus} events, folded in Java to month-end
 *       point-in-time counts by type (CONSULTANT / STUDENT / STAFF) —
 *       same semantics as {@code CxoPeopleService.headcountGrowth}, extended
 *       with leave counts and hire/termination transitions.</li>
 *   <li>Simulation seeds: {@code fact_salary_monthly} (per-type salary),
 *       {@code work_full_optimized} (realized rate + billable hours per type).</li>
 * </ul>
 *
 * <p>The pure fold/assembly logic is exposed as static package-visible members
 * so the DB-free fast test tier can exercise it without a database.</p>
 */
@JBossLog
@ApplicationScoped
public class GrowthAnalyticsService {

    /** First month of fact_company_revenue_mat — the timeline's left edge. */
    static final YearMonth REVENUE_START = YearMonth.of(2017, 7);

    /** First month with GL-derived cost facts (fact_opex / finance_details). */
    static final String COST_DATA_FROM_KEY = "202407";

    /** Employment statuses that count as "employed" (ACTIVE or on leave). */
    static final Set<String> EMPLOYED_STATUSES =
            Set.of("ACTIVE", "MATERNITY_LEAVE", "NON_PAY_LEAVE", "PAID_LEAVE");

    /** Leave subset of {@link #EMPLOYED_STATUSES}. */
    static final Set<String> LEAVE_STATUSES =
            Set.of("MATERNITY_LEAVE", "NON_PAY_LEAVE", "PAID_LEAVE");

    /** Employee types included in every count (EXTERNAL is excluded). */
    static final Set<String> COUNTED_TYPES = Set.of("CONSULTANT", "STUDENT", "STAFF");

    @Inject
    EntityManager em;

    @Inject
    DistributionAwareOpexProvider opexProvider;

    @Inject
    BankLiquidityService bankLiquidityService;

    // ========================================================================
    // Public API — group-level only. Liquidity is managed by moving money
    // between the three companies, so this tab deliberately has no company
    // filter; every series is the combined total.
    // ========================================================================

    public GrowthTimelineDTO getTimeline(CostSource costSource) {
        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);

        Map<String, Double> revenueByMonth = queryMonthlyRevenue(null, monthKey(current));
        Map<String, Double> opexByMonth = opexProvider.getMonthlyOpex(
                COST_DATA_FROM_KEY, monthKey(current), null, costSource);
        Map<String, Double> glDirectByMonth = queryMonthlyGlDirectCost(
                null, COST_DATA_FROM_KEY, monthKey(current), costSource);
        List<StatusRow> statusRows = queryStatusRows(null);

        Map<String, HeadcountMonth> headcountByMonth =
                foldHeadcount(statusRows, REVENUE_START, current, today);

        List<GroupFlowMonth> bankFlows = bankLiquidityService.groupMonthlyFlows();
        Map<String, Double> bankBalanceByMonth = cumulativeBalances(bankFlows);
        Map<String, Double> bankFlowByMonth = new HashMap<>();
        for (GroupFlowMonth flow : bankFlows) bankFlowByMonth.put(flow.monthKey(), flow.totalFlow());

        List<GrowthTimelineMonthDTO> months = assembleTimeline(
                REVENUE_START, current, revenueByMonth, opexByMonth, glDirectByMonth,
                headcountByMonth, bankBalanceByMonth, bankFlowByMonth);
        return new GrowthTimelineDTO(months, COST_DATA_FROM_KEY, monthKey(current));
    }

    public GrowthBaselineDTO getSimulationBaseline(CostSource costSource) {
        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);
        // GL cost booking lags ~3–4 weeks, so the last calendar month is not a
        // complete month yet. All financial windows therefore end at the month
        // BEFORE last (e.g. June 30 when today is August 29) — otherwise the
        // TTM EBITDA overstates because recent costs are missing.
        YearMonth asOf = current.minusMonths(2);
        YearMonth ttmFrom = asOf.minusMonths(11);
        String ttmFromKey = monthKey(ttmFrom);
        String ttmToKey = monthKey(asOf);

        // People — current counts (clamped to today so future-dated status rows
        // don't count yet) and TTM average ACTIVE per type for hour normalization.
        List<StatusRow> statusRows = queryStatusRows(null);
        Map<String, HeadcountMonth> currentFold = foldHeadcount(statusRows, current, current, today);
        HeadcountMonth now = currentFold.getOrDefault(monthKey(current), HeadcountMonth.EMPTY);
        Map<String, HeadcountMonth> ttmFold = foldHeadcount(statusRows, ttmFrom, asOf, today);
        double avgConsultants = averageOf(ttmFold, HeadcountMonth::consultants);
        double avgStudents = averageOf(ttmFold, HeadcountMonth::students);

        // Salary per person per type — latest complete month with salary facts.
        String salaryMonthKey = queryLatestSalaryMonthKey(null, ttmToKey);
        Map<String, SalaryStats> salaryByType = querySalaryByType(null, salaryMonthKey);

        // Payroll overhead — GL payroll (SALARIES) TTM vs. salary-fact TTM.
        List<OpexRow> opexRows = opexProvider.getDistributionAwareOpex(
                ttmFromKey, ttmToKey, null, null, null, costSource);
        double payrollTtm = 0d;
        double nonPayrollTtm = 0d;
        for (OpexRow row : opexRows) {
            if (row.isPayrollFlag()) payrollTtm += row.opexAmountDkk();
            else nonPayrollTtm += row.opexAmountDkk();
        }
        double salaryFactTtm = querySalaryFactTtm(null, ttmFromKey, ttmToKey);
        Double overheadFactor = salaryFactTtm > 0 ? payrollTtm / salaryFactTtm : null;

        // Revenue + cost maps over the whole cost-data era — the TTM figures are
        // subsets, and the cash-conversion measurement needs the full window.
        Map<String, Double> revenueByMonth =
                queryMonthlyRevenue(COST_DATA_FROM_KEY, ttmToKey);
        Map<String, Double> opexByMonth = opexProvider.getMonthlyOpex(
                COST_DATA_FROM_KEY, ttmToKey, null, costSource);
        Map<String, Double> glDirectByMonth =
                queryMonthlyGlDirectCost(null, COST_DATA_FROM_KEY, ttmToKey, costSource);
        double revenueTtm = sumWindow(revenueByMonth, ttmFromKey, ttmToKey);
        double glDirectTtm = sumWindow(glDirectByMonth, ttmFromKey, ttmToKey);

        // Billable work per type (TTM to asOf).
        Map<String, WorkStats> workByType =
                queryWorkStatsByType(null, ttmFrom.atDay(1), asOf.plusMonths(1).atDay(1));
        WorkStats consultantWork = workByType.get("CONSULTANT");
        WorkStats studentWork = workByType.get("STUDENT");

        SalaryStats consultantSalary = salaryByType.get("CONSULTANT");
        SalaryStats studentSalary = salaryByType.get("STUDENT");
        SalaryStats staffSalary = salaryByType.get("STAFF");

        // Liquidity — combined imported bank flows across all three companies.
        List<GroupFlowMonth> bankFlows = bankLiquidityService.groupMonthlyFlows();
        Double bankBalance = bankFlows.isEmpty() ? null
                : bankFlows.stream().mapToDouble(GroupFlowMonth::totalFlow).sum();
        Double bankBalanceBooked = bankFlows.isEmpty() ? null
                : bankFlows.stream().mapToDouble(GroupFlowMonth::bookedFlow).sum();
        Map<String, Double> ebitdaByMonth = new HashMap<>();
        for (Map.Entry<String, Double> e : revenueByMonth.entrySet()) {
            String mk = e.getKey();
            ebitdaByMonth.put(mk, e.getValue()
                    - opexByMonth.getOrDefault(mk, 0d)
                    - glDirectByMonth.getOrDefault(mk, 0d));
        }
        Double conversion = measureCashConversion(bankFlows, ebitdaByMonth, COST_DATA_FROM_KEY, ttmToKey);
        List<Double> seasonal = seasonalFlowPattern(bankFlows);
        int lastCompleteFy = fiscalYearOf(asOf) - 1;
        Double lastFyDividend = lastFiscalYearDividend(bankFlows, lastCompleteFy);
        Integer dividendMonth = dominantDividendMonth(bankFlows);

        return new GrowthBaselineDTO(
                ttmToKey,
                now.consultants(),
                now.students(),
                now.staff(),
                consultantSalary != null ? consultantSalary.avgMonthlyCost() : null,
                studentSalary != null ? studentSalary.avgMonthlyCost() : null,
                staffSalary != null ? staffSalary.avgMonthlyCost() : null,
                overheadFactor,
                nonPayrollTtm / 12d,
                revenueTtm > 0 ? Math.max(0d, glDirectTtm / revenueTtm) : 0d,
                consultantWork != null ? consultantWork.realizedRate() : null,
                studentWork != null ? studentWork.realizedRate() : null,
                perPersonMonthlyHours(consultantWork, avgConsultants),
                perPersonMonthlyHours(studentWork, avgStudents),
                revenueTtm,
                payrollTtm + nonPayrollTtm + glDirectTtm,
                bankBalance,
                bankBalanceBooked,
                conversion,
                seasonal,
                lastFyDividend,
                dividendMonth,
                payrollTtm / 12d);
    }

    // ========================================================================
    // Pure logic (DB-free testable)
    // ========================================================================

    /** One userstatus event row. */
    record StatusRow(String useruuid, String status, String type, LocalDate statusdate) {}

    /** Point-in-time counts and transition counts for one month. */
    record HeadcountMonth(long consultants, long students, long staff, long onLeave,
                          long hires, long terminations) {
        static final HeadcountMonth EMPTY = new HeadcountMonth(0, 0, 0, 0, 0, 0);
    }

    /** Per-type salary aggregates (persons, average monthly cost in DKK). */
    record SalaryStats(long persons, double avgMonthlyCost) {}

    /** Per-type billable work aggregates over a window. */
    record WorkStats(double billableHours, Double realizedRate) {}

    /**
     * Folds raw {@code userstatus} events into month-end point-in-time counts and
     * per-month employment transitions for {@code [from..to]} inclusive.
     *
     * <p>Point-in-time rule: a user's state in month M is their latest status row
     * dated on or before min(last day of M, {@code today}) — the {@code today}
     * clamp keeps future-dated rows (planned starts/terminations) out of the
     * current month. EMPLOYED people (ACTIVE or on leave) count under their type
     * — someone on maternity leave is still an employee, and this matches the
     * HR &amp; People tab's headcount. The leave subset is additionally counted
     * in {@code onLeave}; PREBOARDING and TERMINATED count nowhere.</p>
     *
     * <p>Transition rule (independent of the clamp, bucketed by the row's own
     * date): a row with an employed status following no row / TERMINATED /
     * PREBOARDING is a hire; a TERMINATED row following an employed status is a
     * termination. Status flips between ACTIVE and leave are neither.</p>
     */
    static Map<String, HeadcountMonth> foldHeadcount(
            List<StatusRow> rows, YearMonth from, YearMonth to, LocalDate today) {

        // Group per user, preserving the (statusdate, insertion) order of the input.
        Map<String, List<StatusRow>> byUser = new HashMap<>();
        for (StatusRow row : rows) {
            if (row.type() == null || !COUNTED_TYPES.contains(row.type())) continue;
            byUser.computeIfAbsent(row.useruuid(), k -> new ArrayList<>()).add(row);
        }
        byUser.values().forEach(list ->
                list.sort(java.util.Comparator.comparing(StatusRow::statusdate)));

        // Transition counts, bucketed by event month: [0]=hires, [1]=terminations.
        Map<String, long[]> transitionsByMonth = new HashMap<>();
        for (List<StatusRow> userRows : byUser.values()) {
            String prev = null;
            for (StatusRow row : userRows) {
                boolean wasEmployed = prev != null && EMPLOYED_STATUSES.contains(prev);
                boolean isEmployed = EMPLOYED_STATUSES.contains(row.status());
                String mk = monthKey(YearMonth.from(row.statusdate()));
                if (isEmployed && !wasEmployed) {
                    transitionsByMonth.computeIfAbsent(mk, k -> new long[2])[0]++;
                } else if ("TERMINATED".equals(row.status()) && wasEmployed) {
                    transitionsByMonth.computeIfAbsent(mk, k -> new long[2])[1]++;
                }
                prev = row.status();
            }
        }

        // Point-in-time counts per month.
        Map<String, HeadcountMonth> result = new TreeMap<>();
        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
            LocalDate cutoff = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
            long consultants = 0;
            long students = 0;
            long staff = 0;
            long onLeave = 0;
            for (List<StatusRow> userRows : byUser.values()) {
                StatusRow latest = null;
                for (StatusRow row : userRows) {
                    if (row.statusdate().isAfter(cutoff)) break;
                    latest = row;
                }
                if (latest == null) continue;
                if (EMPLOYED_STATUSES.contains(latest.status())) {
                    switch (latest.type()) {
                        case "CONSULTANT" -> consultants++;
                        case "STUDENT" -> students++;
                        case "STAFF" -> staff++;
                        default -> { /* filtered above */ }
                    }
                    if (LEAVE_STATUSES.contains(latest.status())) {
                        onLeave++;
                    }
                }
            }
            String mk = monthKey(ym);
            long[] tr = transitionsByMonth.getOrDefault(mk, new long[2]);
            result.put(mk, new HeadcountMonth(consultants, students, staff, onLeave, tr[0], tr[1]));
        }
        return result;
    }

    /**
     * Assembles the timeline DTO list for {@code [from..to]}: revenue defaults to
     * 0 for gap months; cost fields stay null before {@link #COST_DATA_FROM_KEY}
     * or when the month has no cost rows at all.
     */
    static List<GrowthTimelineMonthDTO> assembleTimeline(
            YearMonth from, YearMonth to,
            Map<String, Double> revenueByMonth,
            Map<String, Double> opexByMonth,
            Map<String, Double> glDirectByMonth,
            Map<String, HeadcountMonth> headcountByMonth,
            Map<String, Double> bankBalanceByMonth,
            Map<String, Double> bankFlowByMonth) {

        List<GrowthTimelineMonthDTO> result = new ArrayList<>();
        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
            String mk = monthKey(ym);
            boolean costEra = mk.compareTo(COST_DATA_FROM_KEY) >= 0;
            Double opex = costEra ? opexByMonth.getOrDefault(mk, 0d) : null;
            Double glDirect = costEra ? glDirectByMonth.getOrDefault(mk, 0d) : null;
            Double totalCost = costEra ? opex + glDirect : null;
            HeadcountMonth hc = headcountByMonth.getOrDefault(mk, HeadcountMonth.EMPTY);
            result.add(new GrowthTimelineMonthDTO(
                    mk,
                    ym.getYear(),
                    ym.getMonthValue(),
                    fiscalYearOf(ym),
                    revenueByMonth.getOrDefault(mk, 0d),
                    opex,
                    glDirect,
                    totalCost,
                    bankBalanceByMonth.get(mk),
                    bankFlowByMonth.get(mk),
                    hc.consultants(),
                    hc.students(),
                    hc.staff(),
                    hc.onLeave(),
                    hc.hires(),
                    hc.terminations()));
        }
        return result;
    }

    /**
     * End-of-month combined balance per month: running sum of the (chronological)
     * flow series. Cumulative flows equal the accounting balance because opening
     * entries are excluded at import — verified to the øre for all three
     * companies. Months between the first and last flow month with no row still
     * get a balance (the running sum carries forward).
     */
    static Map<String, Double> cumulativeBalances(List<GroupFlowMonth> flows) {
        Map<String, Double> result = new HashMap<>();
        if (flows.isEmpty()) return result;
        double running = 0;
        YearMonth cursor = null;
        Map<String, Double> byKey = new HashMap<>();
        for (GroupFlowMonth flow : flows) byKey.put(flow.monthKey(), flow.totalFlow());
        YearMonth first = parseMonthKey(flows.get(0).monthKey());
        YearMonth last = parseMonthKey(flows.get(flows.size() - 1).monthKey());
        for (cursor = first; !cursor.isAfter(last); cursor = cursor.plusMonths(1)) {
            String mk = monthKey(cursor);
            running += byKey.getOrDefault(mk, 0d);
            result.put(mk, running);
        }
        return result;
    }

    /**
     * Measured EBITDA→cash conversion: Σ(non-dividend bank flow) ÷ Σ(EBITDA)
     * over the months in {@code [fromKey..toKey]} where both series exist.
     * Null when the window is empty or EBITDA is non-positive.
     */
    static Double measureCashConversion(
            List<GroupFlowMonth> flows, Map<String, Double> ebitdaByMonth,
            String fromKey, String toKey) {
        double flowSum = 0;
        double ebitdaSum = 0;
        boolean any = false;
        for (GroupFlowMonth flow : flows) {
            String mk = flow.monthKey();
            if (mk.compareTo(fromKey) < 0 || mk.compareTo(toKey) > 0) continue;
            Double ebitda = ebitdaByMonth.get(mk);
            if (ebitda == null) continue;
            flowSum += flow.totalFlow() - flow.dividendFlow();
            ebitdaSum += ebitda;
            any = true;
        }
        if (!any || ebitdaSum <= 0) return null;
        return flowSum / ebitdaSum;
    }

    /**
     * Median intra-year cash-flow deviation per calendar month, measured on
     * non-dividend flows across complete fiscal years, re-centered to sum ≈ 0.
     * Index 0 = January. Returns an empty list when fewer than two complete
     * fiscal years of bank data exist.
     */
    static List<Double> seasonalFlowPattern(List<GroupFlowMonth> flows) {
        // Group non-dividend flows by fiscal year; keep only complete (12-month) years.
        Map<Integer, Map<Integer, Double>> byFy = new TreeMap<>();
        for (GroupFlowMonth flow : flows) {
            YearMonth ym = parseMonthKey(flow.monthKey());
            byFy.computeIfAbsent(fiscalYearOf(ym), k -> new HashMap<>())
                    .merge(ym.getMonthValue(), flow.totalFlow() - flow.dividendFlow(), Double::sum);
        }
        List<double[]> residualYears = new ArrayList<>();
        for (Map<Integer, Double> months : byFy.values()) {
            if (months.size() < 12) continue;
            double mean = months.values().stream().mapToDouble(Double::doubleValue).sum() / 12d;
            double[] residuals = new double[12];
            for (Map.Entry<Integer, Double> e : months.entrySet()) {
                residuals[e.getKey() - 1] = e.getValue() - mean;
            }
            residualYears.add(residuals);
        }
        if (residualYears.size() < 2) return List.of();

        double[] medians = new double[12];
        for (int m = 0; m < 12; m++) {
            double[] values = new double[residualYears.size()];
            for (int y = 0; y < residualYears.size(); y++) values[y] = residualYears.get(y)[m];
            java.util.Arrays.sort(values);
            int n = values.length;
            medians[m] = n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2d;
        }
        double center = java.util.Arrays.stream(medians).average().orElse(0);
        List<Double> result = new ArrayList<>(12);
        for (double median : medians) result.add(median - center);
        return result;
    }

    /** Absolute dividend outflow total in the given fiscal year; null when zero/none. */
    static Double lastFiscalYearDividend(List<GroupFlowMonth> flows, int fiscalYear) {
        double sum = 0;
        for (GroupFlowMonth flow : flows) {
            if (fiscalYearOf(parseMonthKey(flow.monthKey())) == fiscalYear) {
                sum += flow.dividendFlow();
            }
        }
        return sum != 0 ? Math.abs(sum) : null;
    }

    /** Calendar month (1–12) with the largest historical dividend outflows; null when none. */
    static Integer dominantDividendMonth(List<GroupFlowMonth> flows) {
        double[] byMonth = new double[12];
        for (GroupFlowMonth flow : flows) {
            byMonth[parseMonthKey(flow.monthKey()).getMonthValue() - 1] += Math.abs(flow.dividendFlow());
        }
        int best = -1;
        double bestValue = 0;
        for (int m = 0; m < 12; m++) {
            if (byMonth[m] > bestValue) {
                bestValue = byMonth[m];
                best = m;
            }
        }
        return best >= 0 ? best + 1 : null;
    }

    static double sumWindow(Map<String, Double> byMonth, String fromKey, String toKey) {
        double sum = 0;
        for (Map.Entry<String, Double> e : byMonth.entrySet()) {
            if (e.getKey().compareTo(fromKey) >= 0 && e.getKey().compareTo(toKey) <= 0) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    static YearMonth parseMonthKey(String monthKey) {
        return YearMonth.of(Integer.parseInt(monthKey.substring(0, 4)),
                Integer.parseInt(monthKey.substring(4, 6)));
    }

    /** Fiscal year (July 1 → June 30) a month belongs to, named by its starting calendar year. */
    static int fiscalYearOf(YearMonth ym) {
        return ym.getMonthValue() >= 7 ? ym.getYear() : ym.getYear() - 1;
    }

    static String monthKey(YearMonth ym) {
        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    static Double perPersonMonthlyHours(WorkStats work, double avgHeadcount) {
        if (work == null || avgHeadcount <= 0) return null;
        return work.billableHours() / avgHeadcount / 12d;
    }

    private static double averageOf(Map<String, HeadcountMonth> fold,
                                    java.util.function.ToLongFunction<HeadcountMonth> field) {
        if (fold.isEmpty()) return 0d;
        return fold.values().stream().mapToLong(field).average().orElse(0d);
    }

    // ========================================================================
    // Queries
    // ========================================================================

    /**
     * Monthly GROUP external net revenue from live invoices — INVOICE + PHANTOM
     * − external CREDIT_NOTEs, bucketed by the WORK PERIOD the invoice covers
     * ({@code invoices.year}/{@code month}), matching the Annual P&amp;L's
     * default work-period basis. Invoice-date bucketing was measured to drop a
     * full month of revenue at the fiscal-year edge (June work is invoiced in
     * July): FY25/26 work-period 146.8M vs invoice-date 136.8M — only the
     * former reconciles with the Executive Summary's accumulated EBITDA.
     *
     * <p>Deliberately NOT {@code fact_company_revenue_mat}: that table's
     * {@code internal_dkk} is seller-side only (measured FY25/26: +28.7M on the
     * subsidiaries, 0 on the buyer), so summing it across companies double-counts
     * intercompany work. This SQL mirrors the Executive Summary's group
     * invoice-revenue netting ({@code buildGroupInvoiceRevenueSql}: every
     * INTERNAL nets to 0 within the group, so it is simply omitted) and matches
     * the fact table exactly in the years before intercompany billing existed.
     * Null bounds mean open-ended.</p>
     */
    private Map<String, Double> queryMonthlyRevenue(String fromKey, String toKey) {
        String effectiveFromKey = fromKey != null ? fromKey : monthKey(REVENUE_START);
        String effectiveToKey = toKey != null ? toKey : monthKey(YearMonth.from(LocalDate.now()));

        String sql = "SELECT CONCAT(i.year, LPAD(i.month, 2, '0')) AS month_key, " +
                "COALESCE(SUM(ii.rate * ii.hours " +
                "  * CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END " +
                "  * CASE WHEN i.currency = 'DKK' THEN 1 ELSE COALESCE(cur.conversion, 1) END), 0) AS net_revenue " +
                "FROM invoiceitems ii " +
                "JOIN invoices i ON ii.invoiceuuid = i.uuid " +
                "LEFT JOIN currences cur ON cur.currency = i.currency " +
                "  AND cur.month = DATE_FORMAT(i.invoicedate, '%Y%m') " +
                "WHERE i.status = 'CREATED' " +
                "  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE') " +
                // External credit notes only — internal CNs belong to the omitted
                // internal netting (see Invoice.isInternalCreditNote()).
                "  AND (i.type <> 'CREDIT_NOTE' OR i.debtor_companyuuid IS NULL) " +
                "  AND ii.rate IS NOT NULL AND ii.hours IS NOT NULL " +
                "  AND i.month BETWEEN 1 AND 12 " +
                "  AND CONCAT(i.year, LPAD(i.month, 2, '0')) BETWEEN :fromKey AND :toKey " +
                "GROUP BY CONCAT(i.year, LPAD(i.month, 2, '0'))";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromKey", effectiveFromKey);
        query.setParameter("toKey", effectiveToKey);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        Map<String, Double> result = new HashMap<>();
        for (Tuple row : runTupleQuery(query, "queryMonthlyRevenue")) {
            result.put(row.get("month_key", String.class), toDouble(row.get("net_revenue")));
        }
        return result;
    }

    /**
     * Monthly GL DIRECT_COSTS (external subcontractors only) from finance_details.
     * Mirrors {@code CostAnalyticsResource.buildMonthlyGlDirectCostSql} exactly:
     * the intercompany transfer-price accounts (3050/3055/3070/3075/1350) are
     * excluded because that transfer price lives in the invoice-revenue netting
     * and must never also be read from the GL as cost.
     */
    private Map<String, Double> queryMonthlyGlDirectCost(
            Set<String> companyIds, String fromKey, String toKey, CostSource costSource) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT DATE_FORMAT(fd.expensedate, '%Y%m') AS month_key, " +
                "       COALESCE(SUM(fd.amount), 0.0) AS gl_direct_cost " +
                "FROM finance_details fd " +
                "INNER JOIN accounting_accounts aa " +
                "    ON fd.accountnumber = aa.account_code " +
                "    AND fd.companyuuid  = aa.companyuuid " +
                "WHERE aa.cost_type = 'DIRECT_COSTS' " +
                "  AND fd.accountnumber NOT IN (3050, 3055, 3070, 3075, 1350) " +
                "  AND DATE_FORMAT(fd.expensedate, '%Y%m') BETWEEN :fromKey AND :toKey " +
                "  AND fd.amount != 0 " +
                "  AND fd.postingstatus IN (:postingStatuses) " +
                (hasCompanyFilter ? "AND fd.companyuuid IN (:companyIds) " : "") +
                "GROUP BY DATE_FORMAT(fd.expensedate, '%Y%m')";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("postingStatuses", costSource.postingStatusNames());
        if (hasCompanyFilter) query.setParameter("companyIds", companyIds);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        Map<String, Double> result = new HashMap<>();
        for (Tuple row : runTupleQuery(query, "queryMonthlyGlDirectCost")) {
            result.put(row.get("month_key", String.class), toDouble(row.get("gl_direct_cost")));
        }
        return result;
    }

    /** All userstatus events for the counted types, oldest first per user. */
    private List<StatusRow> queryStatusRows(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT us.useruuid AS useruuid, us.status AS status, " +
                "us.type AS type, us.statusdate AS statusdate " +
                "FROM userstatus us " +
                "WHERE us.type IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                (hasCompanyFilter ? "AND us.companyuuid IN (:companyIds) " : "") +
                "ORDER BY us.useruuid, us.statusdate, us.created_at";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) query.setParameter("companyIds", companyIds);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        List<StatusRow> result = new ArrayList<>();
        for (Tuple row : runTupleQuery(query, "queryStatusRows")) {
            Object rawDate = row.get("statusdate");
            LocalDate statusdate = rawDate instanceof LocalDate ld
                    ? ld
                    : ((java.sql.Date) rawDate).toLocalDate();
            result.add(new StatusRow(
                    row.get("useruuid", String.class),
                    row.get("status", String.class),
                    row.get("type", String.class),
                    statusdate));
        }
        return result;
    }

    /** Latest month key with salary facts, at or before {@code maxKey}. */
    private String queryLatestSalaryMonthKey(Set<String> companyIds, String maxKey) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT MAX(month_key) AS mk FROM fact_salary_monthly " +
                "WHERE month_key <= :maxKey " +
                (hasCompanyFilter ? "AND companyuuid IN (:companyIds) " : "");
        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("maxKey", maxKey);
        if (hasCompanyFilter) query.setParameter("companyIds", companyIds);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        List<Tuple> rows = runTupleQuery(query, "queryLatestSalaryMonthKey");
        return rows.isEmpty() ? null : rows.get(0).get("mk", String.class);
    }

    /**
     * Average monthly cost per person by employee type for one month.
     *
     * <p>Unit normalization: {@code fact_salary_monthly} stores NORMAL salaries in
     * DKK ({@code effective_salary} = monthly salary) but HOURLY employees —
     * students, hourly staff — in øre ({@code effective_salary} = hourly rate in
     * øre, {@code salary_sum} = hours × rate × 1.0045 in øre; see
     * V210__Create_fact_salary_monthly_view.sql). HOURLY monthly cost is therefore
     * {@code salary_sum / 100}.</p>
     */
    private Map<String, SalaryStats> querySalaryByType(Set<String> companyIds, String salaryMonthKey) {
        if (salaryMonthKey == null) return Map.of();
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT x.employee_type AS employee_type, COUNT(*) AS persons, " +
                "AVG(x.monthly_cost) AS avg_monthly_cost FROM ( " +
                "  SELECT useruuid, employee_type, " +
                "    SUM(CASE WHEN salary_type = 'HOURLY' THEN salary_sum / 100 " +
                "             ELSE effective_salary END) AS monthly_cost " +
                "  FROM fact_salary_monthly " +
                "  WHERE month_key = :monthKey " +
                "    AND employee_status = 'ACTIVE' " +
                "    AND effective_salary > 0 " +
                "    AND employee_type IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                (hasCompanyFilter ? "    AND companyuuid IN (:companyIds) " : "") +
                "  GROUP BY useruuid, employee_type " +
                ") x GROUP BY x.employee_type";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("monthKey", salaryMonthKey);
        if (hasCompanyFilter) query.setParameter("companyIds", companyIds);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        Map<String, SalaryStats> result = new HashMap<>();
        for (Tuple row : runTupleQuery(query, "querySalaryByType")) {
            result.put(row.get("employee_type", String.class),
                    new SalaryStats(toLong(row.get("persons")), toDouble(row.get("avg_monthly_cost"))));
        }
        return result;
    }

    /** Total salary-fact cost (unit-normalized, see {@link #querySalaryByType}) over a window. */
    private double querySalaryFactTtm(Set<String> companyIds, String fromKey, String toKey) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT COALESCE(SUM(CASE WHEN salary_type = 'HOURLY' THEN salary_sum / 100 " +
                "ELSE effective_salary END), 0) AS total " +
                "FROM fact_salary_monthly " +
                "WHERE month_key >= :fromKey AND month_key <= :toKey " +
                "  AND employee_status = 'ACTIVE' AND effective_salary > 0 " +
                "  AND employee_type IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                (hasCompanyFilter ? "AND companyuuid IN (:companyIds) " : "");
        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (hasCompanyFilter) query.setParameter("companyIds", companyIds);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        List<Tuple> rows = runTupleQuery(query, "querySalaryFactTtm");
        return rows.isEmpty() ? 0d : toDouble(rows.get(0).get("total"));
    }

    /**
     * Billable hours and duration-weighted realized rate per employee type over a
     * date window. {@code rate > 0} is the billable predicate (the {@code billable}
     * flag has been dead since Dec 2023). Company filter is by
     * {@code contract_company_uuid} — the company the revenue lands in, matching
     * the revenue fact's perspective.
     */
    private Map<String, WorkStats> queryWorkStatsByType(
            Set<String> companyIds, LocalDate fromDate, LocalDate toDateExclusive) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT w.type AS employee_type, " +
                "COALESCE(SUM(w.workduration), 0) AS billable_hours, " +
                "SUM(w.workduration * w.rate) / NULLIF(SUM(w.workduration), 0) AS realized_rate " +
                "FROM work_full_optimized w " +
                "WHERE w.rate > 0 " +
                "  AND w.registered >= :fromDate AND w.registered < :toDate " +
                "  AND w.type IN ('CONSULTANT', 'STUDENT') " +
                (hasCompanyFilter ? "AND w.contract_company_uuid IN (:companyIds) " : "") +
                "GROUP BY w.type";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDateExclusive);
        if (hasCompanyFilter) query.setParameter("companyIds", companyIds);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        Map<String, WorkStats> result = new HashMap<>();
        for (Tuple row : runTupleQuery(query, "queryWorkStatsByType")) {
            Object rate = row.get("realized_rate");
            result.put(row.get("employee_type", String.class),
                    new WorkStats(toDouble(row.get("billable_hours")),
                            rate == null ? null : ((Number) rate).doubleValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Tuple> runTupleQuery(Query query, String context) {
        try {
            return query.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "%s failed", context);
            throw pe;
        }
    }
}
