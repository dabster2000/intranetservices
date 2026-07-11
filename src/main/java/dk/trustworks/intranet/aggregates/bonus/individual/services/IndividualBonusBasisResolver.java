package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dsl.BonusFormulaException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.dto.DateValueDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

/**
 * Resolves a {@link Basis} label to a live number for a rule's window via a HARD-CODED switch —
 * never reflectively from a field name (no arbitrary-code surface).
 * <p>
 * Only the READY (per-employee) labels are implemented. Company-grain and not-yet-plumbed labels
 * throw {@link UnsupportedOperationException} with a clear message. All fact queries use parameter
 * binding only.
 */
@JBossLog
@ApplicationScoped
public class IndividualBonusBasisResolver {

    @Inject
    RevenueService revenueService;

    @Inject
    EntityManager em;

    /**
     * Resolve the basis amount for {@code [from, to]} (both inclusive).
     * <p>
     * Actuals are only booked up to today. For an ADDITIVE production/hours basis whose window runs PAST
     * today, the not-yet-elapsed tail is filled with a modest <b>run-rate forecast</b> — the booked actuals
     * annualised over the elapsed employed months of the window — so a mid-FY projection reflects a
     * year-end estimate rather than production-to-date. This feeds only the read-time projection: an already
     * settled window ({@code to <= today}, e.g. FY-close materialisation) is unaffected and stays actuals-
     * only. RATIO / LEVEL bases (UTILIZATION, BUDGET_ATTAINMENT, SALARY) are already normalised and are NOT
     * scaled by months (see {@link #isForecastable}).
     *
     * @throws UnsupportedOperationException for {@code FIXED_AMOUNT} (schedule-driven) and company /
     *                                       unplumbed labels.
     */
    public BigDecimal resolveBasisAmount(Basis basis, String userUuid, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        // Forecast only additive bases, only when the window extends past today, and only once part of it
        // has elapsed (from <= today). Everything else keeps the exact actuals-only behaviour.
        if (!isForecastable(basis) || !to.isAfter(today) || from.isAfter(today)) {
            return resolveActual(basis, userUuid, from, to);
        }
        BigDecimal actualToDate = resolveActual(basis, userUuid, from, today);
        int elapsedEmployedMonths = monthsActive(userUuid, from, today);
        int totalMonthsInWindow = monthsBetweenInclusive(from, to);
        return runRate(actualToDate, elapsedEmployedMonths, totalMonthsInWindow);
    }

    /**
     * Resolve one {@code formula} fact variable ({@code production}, {@code utilization}, {@code billableHours},
     * {@code budgetAttainment}, {@code salary}) to a live number over {@code [from, to]}, reusing the SAME
     * forecast-aware {@link #resolveBasisAmount} switch as the declarative path (so a formula's {@code production}
     * matches the tier path's {@code OWN_INVOICED_REVENUE} exactly, including run-rate forecasting for a future
     * window). The curated variable → {@link Basis} mapping lives here, the single owner of fact resolution;
     * {@code basisAmount}/{@code monthsEmployed}/{@code fiscalYear} are supplied directly by the caller and never
     * routed here. An un-mapped name fails safe.
     */
    public BigDecimal resolveVariable(String name, String userUuid, LocalDate from, LocalDate to) {
        Basis basis = switch (name) {
            case "production" -> Basis.OWN_INVOICED_REVENUE;
            case "utilization" -> Basis.UTILIZATION;
            case "billableHours" -> Basis.BILLABLE_HOURS;
            case "budgetAttainment" -> Basis.BUDGET_ATTAINMENT;
            case "salary" -> Basis.SALARY;
            default -> throw new BonusFormulaException("Unknown formula fact variable: " + name);
        };
        return resolveBasisAmount(basis, userUuid, from, to);
    }

    /** Actuals-only resolution (booked facts over {@code [from, to]}) — the pre-forecast hard-coded switch. */
    private BigDecimal resolveActual(Basis basis, String userUuid, LocalDate from, LocalDate to) {
        return switch (basis) {
            case OWN_INVOICED_REVENUE -> ownInvoicedRevenue(userUuid, from, to);
            case BILLABLE_HOURS -> billableHours(userUuid, from, to);
            case UTILIZATION -> utilization(userUuid, from, to);
            case SALARY -> averageMonthlySalary(userUuid, from, to);
            case BUDGET_ATTAINMENT -> budgetAttainment(userUuid, from, to);
            case FIXED_AMOUNT -> throw new UnsupportedOperationException(
                    "FIXED_AMOUNT has no fact basis — the amount is resolved by the schedule");
            case COMPANY_INVOICED_REVENUE, COMPANY_UTILIZATION, COMPANY_EBITDA ->
                    throw new UnsupportedOperationException(
                            "Basis " + basis + " is company-grain and not yet wired for individual bonuses");
        };
    }

    /**
     * Whether run-rate annualisation is meaningful for a basis. Only ADDITIVE production/hours SUMS
     * (OWN_INVOICED_REVENUE, BILLABLE_HOURS) may be scaled by months. RATIO / LEVEL bases (UTILIZATION,
     * BUDGET_ATTAINMENT, SALARY) are already period-normalised — scaling them by month count would wildly
     * inflate them — so they stay actuals-only. FIXED_AMOUNT is schedule-driven (never resolved here).
     * Pure and package-visible for unit testing.
     */
    static boolean isForecastable(Basis basis) {
        return basis == Basis.OWN_INVOICED_REVENUE || basis == Basis.BILLABLE_HOURS;
    }

    /**
     * Run-rate forecast: {@code actualToDate / elapsedEmployedMonths × totalMonthsInWindow}, rounded to øre.
     * Degrades to actuals when nothing has elapsed to extrapolate from ({@code elapsed <= 0}) or the window
     * is already fully elapsed ({@code total <= elapsed}) — never fabricates beyond a straight annualisation.
     * Pure and package-visible for unit testing.
     */
    static BigDecimal runRate(BigDecimal actualToDate, int elapsedEmployedMonths, int totalMonthsInWindow) {
        if (actualToDate == null) return BigDecimal.ZERO;
        if (elapsedEmployedMonths <= 0 || totalMonthsInWindow <= elapsedEmployedMonths) return actualToDate;
        return actualToDate
                .multiply(BigDecimal.valueOf(totalMonthsInWindow))
                .divide(BigDecimal.valueOf(elapsedEmployedMonths), 2, RoundingMode.HALF_UP);
    }

    /** Number of distinct calendar months spanned by {@code [from, to]} (both inclusive). Pure. */
    static int monthsBetweenInclusive(LocalDate from, LocalDate to) {
        return (int) (ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1);
    }

    /**
     * Number of distinct months the employee was ACTIVE in {@code [from, to]} (fact_user_day).
     * Used for {@code proRating.byMonthsEmployedInFy}.
     */
    public int monthsActive(String userUuid, LocalDate from, LocalDate to) {
        Object r = em.createNativeQuery("""
                        SELECT COUNT(DISTINCT (fud.year * 100 + fud.month))
                        FROM fact_user_day fud
                        WHERE fud.useruuid = :userUuid
                          AND fud.status_type = 'ACTIVE'
                          AND fud.document_date >= :from AND fud.document_date <= :to
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return r == null ? 0 : ((Number) r).intValue();
    }

    // --- READY basis arms ---

    /** Own registered production (rate·duration·discount over work_full), summed over the window. */
    private BigDecimal ownInvoicedRevenue(String userUuid, LocalDate from, LocalDate to) {
        // RevenueService uses a half-open window (registered < periodTo); pass to+1 day to include `to`.
        List<DateValueDTO> series = revenueService.getRegisteredRevenueByPeriodAndSingleConsultant(
                userUuid, stringIt(from), stringIt(to.plusDays(1)));
        double sum = series == null ? 0.0 : series.stream()
                .mapToDouble(dv -> dv.getValue() != null ? dv.getValue() : 0.0)
                .sum();
        return BigDecimal.valueOf(sum);
    }

    private BigDecimal billableHours(String userUuid, LocalDate from, LocalDate to) {
        Object r = em.createNativeQuery("""
                        SELECT COALESCE(SUM(fud.registered_billable_hours), 0)
                        FROM fact_user_day fud
                        WHERE fud.useruuid = :userUuid
                          AND fud.status_type = 'ACTIVE'
                          AND fud.document_date >= :from AND fud.document_date <= :to
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return toBigDecimal(r);
    }

    /** Billable ÷ net-available hours over the window (0 when no available hours). */
    private BigDecimal utilization(String userUuid, LocalDate from, LocalDate to) {
        Object[] r = (Object[]) em.createNativeQuery("""
                        SELECT COALESCE(SUM(fud.registered_billable_hours), 0),
                               COALESCE(SUM(fud.net_available_hours), 0)
                        FROM fact_user_day fud
                        WHERE fud.useruuid = :userUuid
                          AND fud.status_type = 'ACTIVE'
                          AND fud.document_date >= :from AND fud.document_date <= :to
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        BigDecimal billable = toBigDecimal(r[0]);
        BigDecimal available = toBigDecimal(r[1]);
        if (available.signum() == 0) return BigDecimal.ZERO;
        return billable.divide(available, 6, java.math.RoundingMode.HALF_UP);
    }

    /** Average monthly gross salary over the window (per-month MAX(salary), averaged). */
    private BigDecimal averageMonthlySalary(String userUuid, LocalDate from, LocalDate to) {
        Object r = em.createNativeQuery("""
                        SELECT COALESCE(AVG(monthly.msal), 0) FROM (
                            SELECT MAX(COALESCE(fud.salary, 0)) AS msal
                            FROM fact_user_day fud
                            WHERE fud.useruuid = :userUuid
                              AND fud.status_type = 'ACTIVE'
                              AND fud.document_date >= :from AND fud.document_date <= :to
                            GROUP BY fud.year, fud.month
                        ) monthly
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return toBigDecimal(r);
    }

    /** Actual registered amount ÷ budgeted revenue (bi_budget_per_day) over the window. */
    private BigDecimal budgetAttainment(String userUuid, LocalDate from, LocalDate to) {
        Object actualObj = em.createNativeQuery("""
                        SELECT COALESCE(SUM(fud.registered_amount), 0)
                        FROM fact_user_day fud
                        WHERE fud.useruuid = :userUuid
                          AND fud.status_type = 'ACTIVE'
                          AND fud.document_date >= :from AND fud.document_date <= :to
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        Object budgetObj = em.createNativeQuery("""
                        SELECT COALESCE(SUM(b.budgetHours * b.rate), 0)
                        FROM bi_budget_per_day b
                        WHERE b.useruuid = :userUuid
                          AND b.document_date >= :from AND b.document_date <= :to
                        """)
                .setParameter("userUuid", userUuid)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        BigDecimal actual = toBigDecimal(actualObj);
        BigDecimal budget = toBigDecimal(budgetObj);
        if (budget.signum() == 0) return BigDecimal.ZERO;
        return actual.divide(budget, 6, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return BigDecimal.valueOf(((Number) o).doubleValue());
    }
}
