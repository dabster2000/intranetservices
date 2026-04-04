package dk.trustworks.intranet.aggregates.finance.services.analytics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.toMonthKey;

/**
 * Canonical revenue data access. Three methods for three use cases:
 *
 * 1. Company revenue (invoice-based, from fact_company_revenue_mat) -- for financial reporting
 * 2. Team revenue (time-registration-based, from fact_user_day) -- for operational metrics
 * 3. Consultant invoiced revenue (from invoiceitems) -- for individual profitability
 *
 * The divergence is intentional and documented.
 */
@JBossLog
@ApplicationScoped
public class RevenueAnalyticsProvider {

    @Inject
    EntityManager em;

    /**
     * Company-level revenue (canonical, invoice-based).
     * Source: fact_company_revenue_mat.net_revenue_dkk
     */
    public double getCompanyRevenue(LocalDate fromDate, LocalDate toDate, Set<String> companyIds) {
        String fromKey = toMonthKey(fromDate);
        String toKey = toMonthKey(toDate);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COALESCE(SUM(r.net_revenue_dkk), 0) AS total_revenue ");
        sql.append("FROM fact_company_revenue_mat r ");
        sql.append("WHERE r.month_key >= :fromKey AND r.month_key <= :toKey ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND r.company_id IN (:companyIds) ");
        }

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Tuple result = (Tuple) query.getSingleResult();
        return ((Number) result.get("total_revenue")).doubleValue();
    }

    /**
     * Team revenue (operational, time-registration-based).
     * Source: fact_user_day.registered_amount with temporal team membership filter.
     */
    public double getTeamRevenue(List<String> memberUuids, LocalDate fromDate, LocalDate toDate) {
        if (memberUuids == null || memberUuids.isEmpty()) return 0.0;

        String sql = "SELECT COALESCE(SUM(fud.registered_amount), 0) AS revenue " +
                "FROM fact_user_day fud " +
                "WHERE fud.useruuid IN (:memberUuids) " +
                "  AND fud.document_date >= :fromDate AND fud.document_date <= :toDate " +
                "  AND fud.consultant_type = 'CONSULTANT' " +
                "  AND fud.status_type = 'ACTIVE'";

        var query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("memberUuids", memberUuids);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        Tuple result = (Tuple) query.getSingleResult();
        return ((Number) result.get("revenue")).doubleValue();
    }

    /**
     * Consultant invoiced revenue (financial truth for profitability).
     * Source: invoiceitems x invoices with currency conversion and credit note handling.
     */
    public double getConsultantInvoicedRevenue(List<String> userIds, LocalDate fromDate, LocalDate toDate) {
        if (userIds == null || userIds.isEmpty()) return 0.0;

        String sql = "SELECT COALESCE(SUM(ii.rate * ii.hours " +
                "  * CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END " +
                "  * CASE WHEN i.currency = 'DKK' THEN 1 ELSE COALESCE(cur.conversion, 1) END), 0) AS revenue " +
                "FROM invoiceitems ii " +
                "JOIN invoices i ON ii.invoiceuuid = i.uuid " +
                "LEFT JOIN currences cur ON cur.currency = i.currency " +
                "  AND cur.month = DATE_FORMAT(i.invoicedate, '%Y%m') " +
                "WHERE i.status = 'CREATED' " +
                "  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE') " +
                "  AND ii.rate IS NOT NULL AND ii.hours IS NOT NULL " +
                "  AND ii.consultantuuid IN (:userIds) " +
                "  AND i.invoicedate >= :fromDate AND i.invoicedate < :toDate";

        var query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("userIds", userIds);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        Tuple result = (Tuple) query.getSingleResult();
        return ((Number) result.get("revenue")).doubleValue();
    }
}
