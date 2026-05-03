package dk.trustworks.intranet.aggregates.sales.services;

import dk.trustworks.intranet.aggregates.sales.dto.cxo.PipelineFunnelStageDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native-SQL-backed service for the CXO Command Center sales tab.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class CxoSalesService {

    /** Per-query timeout for CXO Command Center endpoints (matches the legacy BFF's 15-second budget). */
    static final int CXO_QUERY_TIMEOUT_MS = 15_000;

    /** Display labels keyed by stage_id for the pipeline funnel. Falls back to raw value if missing. */
    private static final Map<String, String> PIPELINE_STAGE_LABELS = Map.of(
            "DETECTED", "Detected",
            "QUALIFIED", "Qualified",
            "SHORTLISTED", "Shortlisted",
            "PROPOSAL", "Proposal",
            "NEGOTIATION", "Negotiation"
    );

    @Inject
    EntityManager em;

    /**
     * Null-safe Tuple value coercion to double. Mirrors the helper in
     * `CxoFinanceService` and `CxoClientService` — null → 0.0, primitives unboxed,
     * Booleans/byte[]/BitSet treated as 1.0/0.0 truthy.
     */
    static double toDouble(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        if (v instanceof byte[] bytes) return (bytes.length > 0 && bytes[0] != 0) ? 1d : 0d;
        if (v instanceof java.util.BitSet bs) return bs.isEmpty() ? 0d : 1d;
        return Double.parseDouble(v.toString());
    }

    // ============================================================================
    // CXO Command Center: Pipeline Funnel
    // ============================================================================

    /**
     * Returns one row per pipeline stage with totals and distinct opportunity count,
     * mirroring the BFF route at {@code /api/cxo/sales/pipeline-funnel}. Rows are
     * ordered DETECTED → QUALIFIED → SHORTLISTED → PROPOSAL → NEGOTIATION via
     * {@code ORDER BY FIELD(stage_id, ...)}.
     *
     * <p>The company filter, when present, is the leading WHERE clause of the
     * query (this is the only Phase 2 sales endpoint where {@code fact_pipeline}
     * has no other predicate). When absent the query has no WHERE clause at all.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return time-ordered list of stage aggregates (may be empty)
     */
    public List<PipelineFunnelStageDTO> pipelineFunnel(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " WHERE company_id IN (:companyIds)" : "";

        String sql = "SELECT " +
                "  stage_id, " +
                "  COALESCE(SUM(expected_revenue_dkk), 0)  AS expected_revenue_dkk, " +
                "  COALESCE(SUM(weighted_pipeline_dkk), 0) AS weighted_pipeline_dkk, " +
                "  COUNT(DISTINCT opportunity_id)          AS opportunity_count " +
                "FROM fact_pipeline" +
                companyFilter + " " +
                "GROUP BY stage_id " +
                "ORDER BY FIELD(stage_id, 'DETECTED', 'QUALIFIED', 'SHORTLISTED', 'PROPOSAL', 'NEGOTIATION')";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<PipelineFunnelStageDTO> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String stageId = row.get("stage_id", String.class);
            String stageLabel = PIPELINE_STAGE_LABELS.getOrDefault(stageId, stageId);
            double expectedRevenue = toDouble(row.get("expected_revenue_dkk"));
            double weightedPipeline = toDouble(row.get("weighted_pipeline_dkk"));
            long opportunityCount = ((Number) row.get("opportunity_count")).longValue();
            result.add(new PipelineFunnelStageDTO(
                    stageId, stageLabel, expectedRevenue, weightedPipeline, opportunityCount));
        }

        log.debugf("pipelineFunnel: %d stages (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
