package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Basis;
import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.dto.DateValueDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
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
     *
     * @throws UnsupportedOperationException for {@code FIXED_AMOUNT} (schedule-driven) and company /
     *                                       unplumbed labels.
     */
    public BigDecimal resolveBasisAmount(Basis basis, String userUuid, LocalDate from, LocalDate to) {
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
