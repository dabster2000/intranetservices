package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticesGrossMarginMonthDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toDouble;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native-SQL-backed service for the CXO Command Center practices tab.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class CxoPracticesService {

    @Inject
    EntityManager em;

    // ============================================================================
    // CXO Command Center: Practice Gross Margin (TTM + delta vs prior 12 months)
    // ============================================================================

    /** The 5 practice codes returned in fixed order regardless of which have rows. */
    private static final List<String> PRACTICES = List.of("PM", "BA", "CYB", "DEV", "SA");

    /** Mutable two-period accumulator used for both revenue and OPEX aggregation. */
    private static final class TwoPeriodAccumulator {
        double current;
        double prior;
    }

    /**
     * Helper: format a {@code LocalDate} as a {@code YYYYMM} month-key string.
     */
    private static String monthKey(LocalDate d) {
        return String.format(Locale.ROOT, "%04d%02d", d.getYear(), d.getMonthValue());
    }

    /**
     * Returns gross-margin % TTM and prior-period delta per practice, mirroring
     * the BFF route at {@code /api/cxo/practices/gross-margin}.
     *
     * <p>Revenue source: consultant-level invoiced revenue from
     * {@code invoiceitems}, joined to {@code invoices}, {@code user.practice}
     * (the consultant's practice), {@code userstatus} (consultant type at
     * invoice date — looked up via correlated MAX(statusdate) subquery), and
     * {@code currences} (FX conversion to DKK). Practices restricted to the
     * five canonical codes.</p>
     *
     * <p>Cost source: {@code fact_opex_mat} filtered to
     * {@code cost_type IN ('OPEX', 'SALARIES')} and the same five practices.
     * Aggregated to per-practice DKK totals over each window.</p>
     *
     * <p>TTM window: 12 complete calendar months ending at month {@code today−1}
     * (current month + most-recent-complete-month are excluded as a registration
     * lag buffer). Prior window: the 12-month window immediately preceding the
     * TTM window.</p>
     *
     * <p>Each practice's row contains both periods plus their margin %s
     * ({@code (revenue − cost) / revenue × 100}, null when revenue is 0) and
     * the absolute delta in percentage points (null when either side is null).</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return list of 5 PracticesGrossMarginMonthDTOs in PM/BA/CYB/DEV/SA order
     */
    public List<PracticesGrossMarginMonthDTO> grossMargin(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();

        // ----------------------------------------------------------------------
        // Compute the two TTM windows in (year, month) form (1-indexed months).
        // ttmEnd  = first day of the most-recent-complete-month       (today − 1 month, day=1)
        // ttmStart = ttmEnd − 12 months
        // priorStart = ttmStart − 12 months
        // ----------------------------------------------------------------------
        LocalDate today = LocalDate.now();
        LocalDate ttmEnd = today.withDayOfMonth(1).minusMonths(1);
        LocalDate ttmStart = ttmEnd.minusYears(1);
        LocalDate priorStart = ttmStart.minusYears(1);

        int ttmStartYear = ttmStart.getYear();
        int ttmStartMonth = ttmStart.getMonthValue();
        int ttmEndYear = ttmEnd.getYear();
        int ttmEndMonth = ttmEnd.getMonthValue();
        int priorStartYear = priorStart.getYear();
        int priorStartMonth = priorStart.getMonthValue();

        String ttmStartKey = monthKey(ttmStart);
        String ttmEndKey = monthKey(ttmEnd);
        String priorStartKey = monthKey(priorStart);

        // ----------------------------------------------------------------------
        // 1) Revenue query — consultant-level revenue from invoiceitems.
        // Period classification via a CASE expression; both windows fetched in
        // one query to halve round-trips. WHERE clause keeps the overall date
        // range = [priorStart, ttmEnd).
        // ----------------------------------------------------------------------
        String revenueCompanyFilter = hasCompanyFilter ? " AND i.companyuuid IN (:companyIds) " : "";
        String revenueSqlTemplate =
                "SELECT " +
                "  u.practice AS practice, " +
                "  CASE " +
                "    WHEN (YEAR(i.invoicedate) > :ttmStartYear " +
                "          OR (YEAR(i.invoicedate) = :ttmStartYear AND MONTH(i.invoicedate) >= :ttmStartMonth)) " +
                "     AND (YEAR(i.invoicedate) < :ttmEndYear " +
                "          OR (YEAR(i.invoicedate) = :ttmEndYear AND MONTH(i.invoicedate) < :ttmEndMonth)) " +
                "      THEN 'current' " +
                "    WHEN (YEAR(i.invoicedate) > :priorStartYear " +
                "          OR (YEAR(i.invoicedate) = :priorStartYear AND MONTH(i.invoicedate) >= :priorStartMonth)) " +
                "     AND (YEAR(i.invoicedate) < :ttmStartYear " +
                "          OR (YEAR(i.invoicedate) = :ttmStartYear AND MONTH(i.invoicedate) < :ttmStartMonth)) " +
                "      THEN 'prior' " +
                "  END AS period, " +
                "  SUM( " +
                "    ii.rate * ii.hours " +
                "    * CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END " +
                "    * CASE WHEN i.currency = 'DKK' THEN 1 ELSE COALESCE(cur.conversion, 1) END " +
                "  ) AS revenue " +
                "FROM invoiceitems ii " +
                "JOIN invoices i ON ii.invoiceuuid = i.uuid " +
                "JOIN `user` u ON u.uuid = ii.consultantuuid " +
                "LEFT JOIN userstatus us ON us.useruuid = ii.consultantuuid " +
                "  AND us.statusdate = ( " +
                "    SELECT MAX(us2.statusdate) FROM userstatus us2 " +
                "    WHERE us2.useruuid = ii.consultantuuid AND us2.statusdate <= i.invoicedate " +
                "  ) " +
                "LEFT JOIN currences cur ON cur.currency = i.currency " +
                "  AND cur.month = DATE_FORMAT(i.invoicedate, '%Y%m') " +
                "WHERE i.status = 'CREATED' " +
                "  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE') " +
                "  AND ii.rate IS NOT NULL AND ii.hours IS NOT NULL " +
                "  AND ii.consultantuuid IS NOT NULL " +
                "  AND us.type = 'CONSULTANT' " +
                "  AND u.practice IN ('PM', 'BA', 'CYB', 'DEV', 'SA') " +
                "  AND (YEAR(i.invoicedate) > :priorStartYear " +
                "       OR (YEAR(i.invoicedate) = :priorStartYear AND MONTH(i.invoicedate) >= :priorStartMonth)) " +
                "  AND (YEAR(i.invoicedate) < :ttmEndYear " +
                "       OR (YEAR(i.invoicedate) = :ttmEndYear AND MONTH(i.invoicedate) < :ttmEndMonth)) " +
                "  __REVENUE_COMPANY_FILTER__ " +
                "GROUP BY u.practice, period " +
                "HAVING period IS NOT NULL " +
                "ORDER BY u.practice, period";
        String revenueSql = revenueSqlTemplate.replace("__REVENUE_COMPANY_FILTER__", revenueCompanyFilter);

        Query revenueQuery = em.createNativeQuery(revenueSql, Tuple.class);
        revenueQuery.setParameter("ttmStartYear", ttmStartYear);
        revenueQuery.setParameter("ttmStartMonth", ttmStartMonth);
        revenueQuery.setParameter("ttmEndYear", ttmEndYear);
        revenueQuery.setParameter("ttmEndMonth", ttmEndMonth);
        revenueQuery.setParameter("priorStartYear", priorStartYear);
        revenueQuery.setParameter("priorStartMonth", priorStartMonth);
        if (hasCompanyFilter) {
            revenueQuery.setParameter("companyIds", companyIds);
        }
        revenueQuery.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> revenueRows;
        try {
            revenueRows = revenueQuery.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "grossMargin revenue query failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

        // ----------------------------------------------------------------------
        // 2) OPEX query — fact_opex_mat per (practice_id, period).
        // ----------------------------------------------------------------------
        String opexCompanyFilter = hasCompanyFilter ? " AND company_id IN (:companyIds) " : "";
        String opexSqlTemplate =
                "SELECT " +
                "  practice_id, " +
                "  CASE " +
                "    WHEN month_key >= :ttmStartKey AND month_key < :ttmEndKey THEN 'current' " +
                "    WHEN month_key >= :priorStartKey AND month_key < :ttmStartKey THEN 'prior' " +
                "  END AS period, " +
                "  COALESCE(SUM(opex_amount_dkk), 0) AS opex_cost " +
                "FROM fact_opex_mat " +
                "WHERE month_key >= :priorStartKey " +
                "  AND month_key < :ttmEndKey " +
                "  AND practice_id IN ('PM', 'BA', 'CYB', 'DEV', 'SA') " +
                "  AND cost_type IN ('OPEX', 'SALARIES') " +
                "  __OPEX_COMPANY_FILTER__ " +
                "GROUP BY practice_id, period " +
                "HAVING period IS NOT NULL " +
                "ORDER BY practice_id, period";
        String opexSql = opexSqlTemplate.replace("__OPEX_COMPANY_FILTER__", opexCompanyFilter);

        Query opexQuery = em.createNativeQuery(opexSql, Tuple.class);
        opexQuery.setParameter("ttmStartKey", ttmStartKey);
        opexQuery.setParameter("ttmEndKey", ttmEndKey);
        opexQuery.setParameter("priorStartKey", priorStartKey);
        if (hasCompanyFilter) {
            opexQuery.setParameter("companyIds", companyIds);
        }
        opexQuery.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> opexRows;
        try {
            opexRows = opexQuery.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "grossMargin opex query failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

        // ----------------------------------------------------------------------
        // 3) Aggregate rows per practice (LinkedHashMap → deterministic order).
        // ----------------------------------------------------------------------
        Map<String, TwoPeriodAccumulator> revenueByPractice = new LinkedHashMap<>();
        for (String p : PRACTICES) revenueByPractice.put(p, new TwoPeriodAccumulator());
        for (Tuple row : revenueRows) {
            String practice = row.get("practice", String.class);
            String period = row.get("period", String.class);
            double rev = toDouble(row.get("revenue"));
            TwoPeriodAccumulator acc = revenueByPractice.get(practice);
            if (acc == null) continue; // practice outside the canonical 5 — silently skip
            if ("current".equals(period)) acc.current = rev;
            else if ("prior".equals(period)) acc.prior = rev;
        }

        Map<String, TwoPeriodAccumulator> opexByPractice = new LinkedHashMap<>();
        for (String p : PRACTICES) opexByPractice.put(p, new TwoPeriodAccumulator());
        for (Tuple row : opexRows) {
            String practiceId = row.get("practice_id", String.class);
            String period = row.get("period", String.class);
            double cost = toDouble(row.get("opex_cost"));
            TwoPeriodAccumulator acc = opexByPractice.get(practiceId);
            if (acc == null) continue;
            if ("current".equals(period)) acc.current = cost;
            else if ("prior".equals(period)) acc.prior = cost;
        }

        // ----------------------------------------------------------------------
        // 4) Build the per-practice DTOs in fixed PRACTICES order.
        // Margin % is null when the period revenue is 0 (avoids divide-by-zero).
        // marginDeltaPts is null when either side is null.
        // ----------------------------------------------------------------------
        List<PracticesGrossMarginMonthDTO> result = new ArrayList<>(PRACTICES.size());
        int dropped = 0;
        for (String practiceId : PRACTICES) {
            TwoPeriodAccumulator rev = revenueByPractice.get(practiceId);
            TwoPeriodAccumulator opex = opexByPractice.get(practiceId);

            double currentRevenue = rev.current;
            double currentCost = opex.current;
            double priorRevenue = rev.prior;
            double priorCost = opex.prior;

            Double currentMarginPct = currentRevenue > 0
                    ? ((currentRevenue - currentCost) / currentRevenue) * 100.0
                    : null;
            Double priorMarginPct = priorRevenue > 0
                    ? ((priorRevenue - priorCost) / priorRevenue) * 100.0
                    : null;
            Double marginDeltaPts = (currentMarginPct != null && priorMarginPct != null)
                    ? currentMarginPct - priorMarginPct
                    : null;

            try {
                result.add(new PracticesGrossMarginMonthDTO(
                        practiceId,
                        currentRevenue,
                        currentCost,
                        currentMarginPct,
                        priorRevenue,
                        priorCost,
                        priorMarginPct,
                        marginDeltaPts
                ));
            } catch (IllegalArgumentException e) {
                dropped++;
                log.warnf("Skipping malformed row in grossMargin (dropped=%d, practiceId=%s): %s",
                        dropped, practiceId, e.getMessage());
            }
        }
        if (dropped > 0) {
            log.warnf("grossMargin dropped %d malformed practice rows out of %d",
                    dropped, PRACTICES.size());
        }

        log.debugf("grossMargin: practices=%d (companyFilter=%s, ttm=%s..%s, prior=%s..%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter),
                ttmStartKey, ttmEndKey, priorStartKey, ttmStartKey);
        return result;
    }
}
