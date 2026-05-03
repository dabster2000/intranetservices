package dk.trustworks.intranet.aggregates.forecast.services;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffPracticeDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native-SQL-backed service for the CXO Command Center forecast tab.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class CxoForecastService {

    static final int CXO_QUERY_TIMEOUT_MS = 15_000;

    @Inject
    EntityManager em;

    static double toDouble(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        if (v instanceof byte[] bytes) return (bytes.length > 0 && bytes[0] != 0) ? 1d : 0d;
        if (v instanceof java.util.BitSet bs) return bs.isEmpty() ? 0d : 1d;
        return Double.parseDouble(v.toString());
    }

    // ============================================================================
    // CXO Command Center: Contract Runoff (Revenue Cliff Projection)
    // ============================================================================

    /**
     * Per-month, per-practice accumulator used during Java-side aggregation of
     * {@code fact_revenue_runoff} rows. Mutable on purpose — the immutable
     * {@link ContractRunoffMonthDTO} is built at the end from the totals here.
     */
    private static final class MonthAccumulator {
        double activeRevenueDkk;
        double expiringRevenueDkk;
        long expiringContractCount;
        double newRevenueDkk;
        double extensionRevenueDkk;
        final List<ContractRunoffPracticeDTO> byPractice = new ArrayList<>();
    }

    /**
     * Returns the contract runoff curve from {@code fact_revenue_runoff},
     * mirroring the BFF route at {@code /api/cxo/forecast/contract-runoff}.
     * Each row in the source represents a (future_month, practice, is_extension,
     * is_expired) bucket; the service aggregates them by month into one
     * {@link ContractRunoffMonthDTO} per future month while preserving every
     * practice slice in {@code byPractice}.
     *
     * <p>Note: this fact table uses {@code company_uuid} (not {@code company_id})
     * for the company filter.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return chronologically-ordered list of monthly runoff aggregates (may be empty)
     */
    public List<ContractRunoffMonthDTO> contractRunoff(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " WHERE company_uuid IN (:companyIds)" : "";

        String sql = "SELECT " +
                "  future_month, " +
                "  COALESCE(practice, 'Unknown') AS practice, " +
                "  COALESCE(is_extension, 0)     AS is_extension, " +
                "  COALESCE(is_expired, 0)       AS is_expired, " +
                "  SUM(monthly_revenue_dkk)      AS total_revenue, " +
                "  SUM(CASE WHEN is_expired = 1 THEN monthly_revenue_dkk ELSE 0 END) AS expiring_revenue, " +
                "  COUNT(DISTINCT CASE WHEN is_expired = 1 THEN contract_uuid END)   AS expiring_count " +
                "FROM fact_revenue_runoff" +
                companyFilter + " " +
                "GROUP BY future_month, practice, is_extension, is_expired " +
                "ORDER BY future_month";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        // LinkedHashMap preserves SQL ORDER BY future_month — no extra sort needed.
        Map<String, MonthAccumulator> byMonth = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String month = row.get("future_month", String.class);
            String practice = row.get("practice", String.class);
            double revenue = toDouble(row.get("total_revenue"));
            double expiring = toDouble(row.get("expiring_revenue"));
            boolean isExpired = ((Number) row.get("is_expired")).intValue() == 1;
            boolean isExtension = ((Number) row.get("is_extension")).intValue() == 1;

            MonthAccumulator acc = byMonth.computeIfAbsent(month, k -> new MonthAccumulator());
            if (isExpired) {
                acc.expiringRevenueDkk += expiring;
                acc.expiringContractCount += ((Number) row.get("expiring_count")).longValue();
            } else {
                acc.activeRevenueDkk += revenue;
            }
            if (isExtension) {
                acc.extensionRevenueDkk += revenue;
            } else {
                acc.newRevenueDkk += revenue;
            }
            acc.byPractice.add(new ContractRunoffPracticeDTO(practice, revenue, isExpired));
        }

        List<ContractRunoffMonthDTO> result = new ArrayList<>(byMonth.size());
        for (Map.Entry<String, MonthAccumulator> e : byMonth.entrySet()) {
            String month = e.getKey();
            MonthAccumulator acc = e.getValue();
            int year = Integer.parseInt(month.substring(0, 4));
            int monthNumber = Integer.parseInt(month.substring(4, 6));
            String monthLabel = Month.of(monthNumber).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + year;
            result.add(new ContractRunoffMonthDTO(
                    month,
                    monthLabel,
                    acc.activeRevenueDkk,
                    acc.expiringRevenueDkk,
                    acc.expiringContractCount,
                    acc.byPractice,
                    acc.newRevenueDkk,
                    acc.extensionRevenueDkk));
        }

        log.debugf("contractRunoff: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
