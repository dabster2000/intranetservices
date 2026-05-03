package dk.trustworks.intranet.aggregates.forecast.services;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffPracticeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthStageDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateDealTypeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRatePracticeDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateStageDTO;
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

    /** Display labels keyed by stage_id for the win-rates view. Falls back to raw value if missing. */
    private static final Map<String, String> STAGE_LABELS = Map.of(
            "DETECTED", "Detected",
            "QUALIFIED", "Qualified",
            "SHORTLISTED", "Shortlisted",
            "PROPOSAL", "Proposal",
            "NEGOTIATION", "Negotiation"
    );

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

    // ============================================================================
    // CXO Command Center: Win Rate Calibration
    // ============================================================================

    /**
     * Per-stage accumulator used during Java-side aggregation of
     * {@code fact_historical_win_rates} rows. Mutable on purpose — the immutable
     * {@link WinRateStageDTO} is built at the end from these totals.
     */
    private static final class StageAccumulator {
        String stageLabel;
        double staticProbabilityPct;
        long sampleSize;
        long wonCount;
        long reachedCount;
        final List<WinRatePracticeDTO> byPractice = new ArrayList<>();
        final List<WinRateDealTypeDTO> byDealType = new ArrayList<>();
    }

    /**
     * Returns the calibrated-vs-static win-rate comparison per pipeline stage
     * from {@code fact_historical_win_rates}, mirroring the BFF route at
     * {@code /api/cxo/forecast/win-rates}.
     *
     * <p><strong>Note:</strong> {@code companyIds} is intentionally accepted but
     * <em>ignored</em> — {@code fact_historical_win_rates} has no
     * {@code company_uuid} column (the view aggregates across all companies).
     * The parameter is preserved on the service signature to keep the API
     * surface uniform with the other CXO endpoints; mirrors BFF route lines
     * 61-63.</p>
     *
     * <p>Stage rows are emitted in canonical funnel order via
     * {@code ORDER BY FIELD(stage_id, ...)} and a {@link LinkedHashMap}
     * preserves that order. The aggregate {@code calibratedWinRatePct} is
     * recomputed from the summed {@code wonCount}/{@code reachedCount} totals
     * (BFF parity); when {@code reachedCount = 0} the static probability is
     * used as the fallback, and {@code deltaPct = calibratedWinRatePct -
     * staticProbabilityPct}.</p>
     *
     * @param companyIds accepted but ignored — see method javadoc
     * @return funnel-ordered list of stage win-rate aggregates (may be empty)
     */
    public List<WinRateStageDTO> winRates(Set<String> companyIds) {
        if (companyIds != null && !companyIds.isEmpty()) {
            log.debugf("winRates: companyIds=%s ignored — fact_historical_win_rates has no company_uuid",
                    companyIds);
        }

        String sql = "SELECT " +
                "  stage_id, " +
                "  practice, " +
                "  deal_type, " +
                "  won_count, " +
                "  reached_count, " +
                "  calibrated_win_rate_pct, " +
                "  static_probability_pct, " +
                "  delta_pct, " +
                "  sample_size " +
                "FROM fact_historical_win_rates " +
                "ORDER BY FIELD(stage_id, 'DETECTED','QUALIFIED','SHORTLISTED','PROPOSAL','NEGOTIATION')";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        // LinkedHashMap preserves SQL FIELD() ordering — no extra sort needed.
        Map<String, StageAccumulator> byStage = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String stageId = row.get("stage_id", String.class);
            String practice = row.get("practice", String.class);
            String dealType = row.get("deal_type", String.class);
            double rowCalibratedPct = toDouble(row.get("calibrated_win_rate_pct"));
            long rowSampleSize = ((Number) row.get("sample_size")).longValue();

            StageAccumulator acc = byStage.computeIfAbsent(stageId, id -> {
                StageAccumulator a = new StageAccumulator();
                a.stageLabel = STAGE_LABELS.getOrDefault(id, id);
                a.staticProbabilityPct = toDouble(row.get("static_probability_pct"));
                return a;
            });
            acc.sampleSize += rowSampleSize;
            acc.wonCount += ((Number) row.get("won_count")).longValue();
            acc.reachedCount += ((Number) row.get("reached_count")).longValue();

            if (practice != null) {
                acc.byPractice.add(new WinRatePracticeDTO(practice, rowCalibratedPct, rowSampleSize));
            }
            if (dealType != null) {
                // BFF dedupes by dealType (first occurrence wins per stage).
                boolean alreadyPresent = false;
                for (WinRateDealTypeDTO d : acc.byDealType) {
                    if (d.dealType().equals(dealType)) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    acc.byDealType.add(new WinRateDealTypeDTO(dealType, rowCalibratedPct, rowSampleSize));
                }
            }
        }

        List<WinRateStageDTO> result = new ArrayList<>(byStage.size());
        for (Map.Entry<String, StageAccumulator> e : byStage.entrySet()) {
            StageAccumulator acc = e.getValue();
            double calibratedWinRatePct = acc.reachedCount > 0
                    ? (acc.wonCount * 100.0 / acc.reachedCount)
                    : acc.staticProbabilityPct;
            double deltaPct = calibratedWinRatePct - acc.staticProbabilityPct;
            result.add(new WinRateStageDTO(
                    e.getKey(),
                    acc.stageLabel,
                    calibratedWinRatePct,
                    acc.staticProbabilityPct,
                    deltaPct,
                    acc.sampleSize,
                    acc.wonCount,
                    acc.reachedCount,
                    acc.byPractice,
                    acc.byDealType));
        }

        log.debugf("winRates: %d stages", Integer.valueOf(result.size()));
        return result;
    }

    // ============================================================================
    // CXO Command Center: Pipeline Health (Coverage Trend)
    // ============================================================================

    /**
     * Per-month accumulator used during Java-side aggregation of
     * {@code fact_pipeline_snapshot} rows. Mutable on purpose — the immutable
     * {@link PipelineHealthMonthDTO} is built at the end from these totals.
     */
    private static final class PipelineMonthAccumulator {
        double totalExpected;
        double totalWeighted;
        final List<PipelineHealthStageDTO> byStage = new ArrayList<>();
    }

    /**
     * Returns the per-month pipeline health snapshot from
     * {@code fact_pipeline_snapshot} joined against the per-month budget target
     * from {@code fact_revenue_budget_mat}, mirroring the BFF route at
     * {@code /api/cxo/forecast/pipeline-health}.
     *
     * <p>Two queries are issued sequentially (the BFF parallelises them, but
     * the JPA EntityManager is single-threaded). The first returns one row per
     * (snapshot_month, stage_id) bucket; the second returns one row per
     * (month_key) budget total. Stage rows are emitted in canonical funnel
     * order via {@code ORDER BY FIELD(stage_id, ...)} and a {@link LinkedHashMap}
     * preserves the {@code snapshot_month} ordering.</p>
     *
     * <p>{@code coverageRatio = totalWeighted / budgetTarget} when the budget
     * target is positive, otherwise {@code 0}. Note the column-name asymmetry:
     * the snapshot table uses {@code company_uuid}, the budget materialised
     * view uses {@code company_id}.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return chronologically-ordered list of monthly pipeline-health aggregates (may be empty)
     */
    public List<PipelineHealthMonthDTO> pipelineHealth(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String pipelineCompanyFilter = hasCompanyFilter ? " WHERE fps.company_uuid IN (:companyIds)" : "";
        String budgetCompanyFilter = hasCompanyFilter ? " WHERE company_id IN (:companyIds)" : "";

        // Query 1: pipeline snapshot rows per (month, stage)
        String pipelineSql = "SELECT " +
                "  fps.snapshot_month, " +
                "  fps.stage_id, " +
                "  COALESCE(SUM(fps.expected_revenue_dkk), 0) AS total_expected, " +
                "  COALESCE(SUM(fps.weighted_pipeline_dkk), 0) AS total_weighted, " +
                "  COUNT(DISTINCT fps.opportunity_uuid) AS opportunity_count " +
                "FROM fact_pipeline_snapshot fps" +
                pipelineCompanyFilter + " " +
                "GROUP BY fps.snapshot_month, fps.stage_id " +
                "ORDER BY fps.snapshot_month, FIELD(fps.stage_id, 'DETECTED','QUALIFIED','SHORTLISTED','PROPOSAL','NEGOTIATION')";

        Query pipelineQuery = em.createNativeQuery(pipelineSql, Tuple.class);
        if (hasCompanyFilter) {
            pipelineQuery.setParameter("companyIds", companyIds);
        }
        pipelineQuery.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> pipelineRows = pipelineQuery.getResultList();

        // Query 2: budget total per month
        String budgetSql = "SELECT " +
                "  month_key, " +
                "  SUM(budget_revenue_dkk) AS budget_target " +
                "FROM fact_revenue_budget_mat" +
                budgetCompanyFilter + " " +
                "GROUP BY month_key";

        Query budgetQuery = em.createNativeQuery(budgetSql, Tuple.class);
        if (hasCompanyFilter) {
            budgetQuery.setParameter("companyIds", companyIds);
        }
        budgetQuery.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> budgetRows = budgetQuery.getResultList();

        Map<String, Double> budgetByMonth = new java.util.HashMap<>();
        for (Tuple row : budgetRows) {
            String monthKey = row.get("month_key", String.class);
            budgetByMonth.put(monthKey, Double.valueOf(toDouble(row.get("budget_target"))));
        }

        // LinkedHashMap preserves SQL ORDER BY snapshot_month — no extra sort needed.
        Map<String, PipelineMonthAccumulator> byMonth = new LinkedHashMap<>();
        for (Tuple row : pipelineRows) {
            String month = row.get("snapshot_month", String.class);
            String stageId = row.get("stage_id", String.class);
            double expected = toDouble(row.get("total_expected"));
            double weighted = toDouble(row.get("total_weighted"));
            long opportunityCount = ((Number) row.get("opportunity_count")).longValue();

            PipelineMonthAccumulator acc = byMonth.computeIfAbsent(month, k -> new PipelineMonthAccumulator());
            acc.totalExpected += expected;
            acc.totalWeighted += weighted;
            acc.byStage.add(new PipelineHealthStageDTO(stageId, expected, weighted, opportunityCount));
        }

        List<PipelineHealthMonthDTO> result = new ArrayList<>(byMonth.size());
        for (Map.Entry<String, PipelineMonthAccumulator> e : byMonth.entrySet()) {
            String month = e.getKey();
            PipelineMonthAccumulator acc = e.getValue();
            double budgetTarget = budgetByMonth.getOrDefault(month, Double.valueOf(0.0)).doubleValue();
            double coverageRatio = budgetTarget > 0.0 ? acc.totalWeighted / budgetTarget : 0.0;
            int year = Integer.parseInt(month.substring(0, 4));
            int monthNumber = Integer.parseInt(month.substring(4, 6));
            String monthLabel = Month.of(monthNumber).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + year;
            result.add(new PipelineHealthMonthDTO(
                    month,
                    monthLabel,
                    acc.totalExpected,
                    acc.totalWeighted,
                    budgetTarget,
                    coverageRatio,
                    acc.byStage));
        }

        log.debugf("pipelineHealth: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
