package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRevenueTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendPointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendQuarterDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendSegmentDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Service behind GET /clients/cxo/industry-revenue-trend.
 *
 * <p>Quarterly invoiced revenue per industry segment for the trailing 12 full
 * calendar quarters, plus a budget-based forecast for the current quarter and
 * the following 6 months (3 forecast quarters in total). Revenue actuals come
 * from {@code fact_client_revenue_mat} (same canonical algorithm as the rest of
 * the Client &amp; Portfolio tab), budget from {@code bi_budget_per_day}
 * (budget hours × rate — the same source {@code fact_revenue_budget} reads,
 * queried directly so the internal client can be excluded consistently with the
 * actuals), and the optional upside overlay from {@code fact_pipeline}
 * (probability-weighted, unwon stages only).</p>
 */
@JBossLog
@ApplicationScoped
public class IndustryRevenueTrendService {

    /** Per-query timeout, matching the BFF's request budget (same as CxoClientService). */
    private static final int QUERY_TIMEOUT_MS = 15_000;

    /** Internal Trustworks client — excluded from all series, matching CxoClientService. */
    private static final String INTERNAL_CLIENT_UUID = "d58bb00b-4474-4250-84eb-d8f77548ddac";

    /** Fixed segment order — also the fixed color order on the frontend. */
    static final List<String> SEGMENT_ORDER = List.of(
            "PUBLIC", "ENERGY", "HEALTH", "FINANCIAL", "EDUCATION", "OTHER");

    static final Map<String, String> SEGMENT_LABELS = Map.of(
            "PUBLIC", "Public Sector",
            "ENERGY", "Energy & Utilities",
            "HEALTH", "Healthcare",
            "FINANCIAL", "Financial Services",
            "EDUCATION", "Education",
            "OTHER", "Other");

    static final String UNATTRIBUTED_CLIENT_NAME = "(Unattributed)";

    /** Actual quarters shown (trailing full quarters before the current one). */
    static final int ACTUAL_QUARTERS = 12;
    /** Forecast quarters: the current quarter + the following 6 months. */
    static final int FORECAST_QUARTERS = 3;
    /** A client counts as "gone quiet" after this many full quarters without revenue. */
    static final int QUIET_QUARTERS = 2;

    @Inject
    EntityManager em;

    public IndustryRevenueTrendDTO getIndustryRevenueTrend(LocalDate asOfDate, Set<String> companyIds) {
        TrendWindow window = TrendWindow.of(asOfDate != null ? asOfDate : LocalDate.now());

        List<RevenueRow> revenueRows = queryRevenue(window, companyIds);
        List<BudgetRow> budgetRows = queryBudget(window, companyIds);
        List<PipelineRow> pipelineRows = queryPipeline(window, companyIds);
        Map<String, String> firstInvoiceMonthByClient = queryFirstInvoiceMonths();

        return assemble(window, revenueRows, budgetRows, pipelineRows, firstInvoiceMonthByClient);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    private List<RevenueRow> queryRevenue(TrendWindow window, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT COALESCE(c.segment, 'OTHER') AS segment, " +
                "fcr.client_id AS client_id, c.name AS client_name, " +
                "fcr.month_key AS month_key, SUM(fcr.net_revenue_dkk) AS revenue " +
                "FROM fact_client_revenue_mat fcr " +
                "LEFT JOIN client c ON c.uuid = fcr.client_id " +
                "WHERE fcr.month_key >= :fromKey AND fcr.month_key <= :toKey " +
                "AND (fcr.client_id IS NULL OR fcr.client_id <> :internalClientId) " +
                (hasCompanies ? "AND fcr.company_id IN (:companyIds) " : "") +
                "GROUP BY COALESCE(c.segment, 'OTHER'), fcr.client_id, c.name, fcr.month_key";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);
        query.setParameter("fromKey", window.actualStartMonthKey());
        query.setParameter("toKey", window.asOfMonthKey());
        query.setParameter("internalClientId", INTERNAL_CLIENT_UUID);
        if (hasCompanies) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<RevenueRow> result = new ArrayList<>(rows.size());
        for (Tuple t : rows) {
            result.add(new RevenueRow(
                    (String) t.get("segment"),
                    (String) t.get("client_id"),
                    (String) t.get("client_name"),
                    t.get("month_key").toString(),
                    ((Number) t.get("revenue")).doubleValue()));
        }
        return result;
    }

    private List<BudgetRow> queryBudget(TrendWindow window, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        // Same source and filters as the fact_revenue_budget view (V427), plus the
        // internal-client exclusion so budget and actuals cover the same population.
        String sql = "SELECT COALESCE(c.segment, 'OTHER') AS segment, " +
                "b.clientuuid AS client_id, c.name AS client_name, " +
                "CONCAT(LPAD(b.year, 4, '0'), LPAD(b.month, 2, '0')) AS month_key, " +
                "SUM(b.budgetHours * b.rate) AS budget " +
                "FROM bi_budget_per_day b " +
                "LEFT JOIN client c ON c.uuid = b.clientuuid " +
                "WHERE b.budgetHours > 0 AND b.document_date IS NOT NULL AND b.companyuuid IS NOT NULL " +
                "AND (b.year * 100 + b.month) >= :fromYm AND (b.year * 100 + b.month) <= :toYm " +
                "AND (b.clientuuid IS NULL OR b.clientuuid <> :internalClientId) " +
                (hasCompanies ? "AND b.companyuuid IN (:companyIds) " : "") +
                "GROUP BY COALESCE(c.segment, 'OTHER'), b.clientuuid, c.name, b.year, b.month";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);
        query.setParameter("fromYm", Integer.parseInt(window.actualStartMonthKey()));
        query.setParameter("toYm", Integer.parseInt(window.forecastEndMonthKey()));
        query.setParameter("internalClientId", INTERNAL_CLIENT_UUID);
        if (hasCompanies) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<BudgetRow> result = new ArrayList<>(rows.size());
        for (Tuple t : rows) {
            result.add(new BudgetRow(
                    (String) t.get("segment"),
                    (String) t.get("client_id"),
                    (String) t.get("client_name"),
                    t.get("month_key").toString(),
                    ((Number) t.get("budget")).doubleValue()));
        }
        return result;
    }

    private List<PipelineRow> queryPipeline(TrendWindow window, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        String sql = "SELECT pl.sector_id AS segment, " +
                "pl.expected_revenue_month_key AS month_key, " +
                "SUM(pl.weighted_pipeline_dkk) AS pipeline " +
                "FROM fact_pipeline pl " +
                "WHERE pl.stage_id NOT IN ('WON') " +
                "AND pl.expected_revenue_month_key >= :fromKey " +
                "AND pl.expected_revenue_month_key <= :toKey " +
                (hasCompanies ? "AND pl.company_id IN (:companyIds) " : "") +
                "GROUP BY pl.sector_id, pl.expected_revenue_month_key";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);
        query.setParameter("fromKey", window.currentQuarterStartMonthKey());
        query.setParameter("toKey", window.forecastEndMonthKey());
        if (hasCompanies) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<PipelineRow> result = new ArrayList<>(rows.size());
        for (Tuple t : rows) {
            Object pipeline = t.get("pipeline");
            result.add(new PipelineRow(
                    (String) t.get("segment"),
                    t.get("month_key").toString(),
                    pipeline == null ? 0d : ((Number) pipeline).doubleValue()));
        }
        return result;
    }

    /** First-ever invoiced month per client, over full history (used for the "new" badge). */
    private Map<String, String> queryFirstInvoiceMonths() {
        String sql = "SELECT fcr.client_id AS client_id, MIN(fcr.month_key) AS first_month " +
                "FROM fact_client_revenue_mat fcr " +
                "WHERE fcr.client_id IS NOT NULL AND fcr.net_revenue_dkk <> 0 " +
                "GROUP BY fcr.client_id";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        Map<String, String> result = new HashMap<>(rows.size());
        for (Tuple t : rows) {
            result.put((String) t.get("client_id"), t.get("first_month").toString());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Pure assembly (package-private for the DB-free test tier)
    // -------------------------------------------------------------------------

    /** Raw revenue row: segment × client × month. */
    record RevenueRow(String segment, String clientUuid, String clientName, String monthKey, double revenueDkk) {}

    /** Raw budget row: segment × client × month. */
    record BudgetRow(String segment, String clientUuid, String clientName, String monthKey, double budgetDkk) {}

    /** Raw weighted-pipeline row: segment × month. */
    record PipelineRow(String segment, String monthKey, double pipelineDkk) {}

    /**
     * The chart's time window, derived from an as-of date.
     * Quarter boundaries are calendar quarters (which coincide with Trustworks
     * fiscal-quarter boundaries, since the fiscal year starts July 1).
     */
    record TrendWindow(LocalDate actualStart, LocalDate currentQuarterStart, LocalDate forecastEndExclusive,
                       LocalDate asOf) {

        static TrendWindow of(LocalDate asOf) {
            LocalDate currentQuarterStart = quarterStart(asOf);
            return new TrendWindow(
                    currentQuarterStart.minusMonths(3L * ACTUAL_QUARTERS),
                    currentQuarterStart,
                    currentQuarterStart.plusMonths(3L * FORECAST_QUARTERS),
                    asOf);
        }

        String actualStartMonthKey() { return monthKey(actualStart); }

        String currentQuarterStartMonthKey() { return monthKey(currentQuarterStart); }

        String asOfMonthKey() { return monthKey(asOf); }

        /** Last month of the forecast window, inclusive. */
        String forecastEndMonthKey() { return monthKey(forecastEndExclusive.minusMonths(1)); }

        String currentQuarterKey() { return quarterKeyOf(currentQuarterStart); }

        String latestFullQuarterKey() { return quarterKeyOf(currentQuarterStart.minusMonths(3)); }

        /** Ordered x-axis: ACTUAL_QUARTERS past quarters + FORECAST_QUARTERS from the current one. */
        List<IndustryTrendQuarterDTO> quarterMetas() {
            List<IndustryTrendQuarterDTO> metas = new ArrayList<>(ACTUAL_QUARTERS + FORECAST_QUARTERS);
            LocalDate cursor = actualStart;
            while (cursor.isBefore(forecastEndExclusive)) {
                boolean actual = cursor.isBefore(currentQuarterStart);
                int quarter = quarterNumber(cursor);
                // The Trustworks fiscal year (Jul 1 → Jun 30) starts with the July quarter.
                String fiscalLabel = cursor.getMonthValue() == 7
                        ? String.format("FY%02d/%02d", cursor.getYear() % 100, (cursor.getYear() + 1) % 100)
                        : null;
                metas.add(new IndustryTrendQuarterDTO(
                        quarterKeyOf(cursor),
                        cursor.getYear(),
                        quarter,
                        actual ? IndustryTrendQuarterDTO.PHASE_ACTUAL : IndustryTrendQuarterDTO.PHASE_FORECAST,
                        fiscalLabel));
                cursor = cursor.plusMonths(3);
            }
            return metas;
        }
    }

    static LocalDate quarterStart(LocalDate date) {
        int firstMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(date.getYear(), firstMonth, 1);
    }

    static int quarterNumber(LocalDate date) {
        return (date.getMonthValue() - 1) / 3 + 1;
    }

    static String quarterKeyOf(LocalDate date) {
        return date.getYear() + "-Q" + quarterNumber(date);
    }

    static String monthKey(LocalDate date) {
        return String.format("%04d%02d", date.getYear(), date.getMonthValue());
    }

    /** Quarter key for a YYYYMM month key. */
    static String quarterKeyForMonthKey(String monthKey) {
        int year = Integer.parseInt(monthKey.substring(0, 4));
        int month = Integer.parseInt(monthKey.substring(4, 6));
        return year + "-Q" + ((month - 1) / 3 + 1);
    }

    /** Fold unknown/missing segment codes into OTHER. */
    static String normalizeSegment(String segment) {
        return segment != null && SEGMENT_ORDER.contains(segment) ? segment : "OTHER";
    }

    static IndustryRevenueTrendDTO assemble(TrendWindow window,
                                            List<RevenueRow> revenueRows,
                                            List<BudgetRow> budgetRows,
                                            List<PipelineRow> pipelineRows,
                                            Map<String, String> firstInvoiceMonthByClient) {
        List<IndustryTrendQuarterDTO> quarters = window.quarterMetas();
        String currentQuarterKey = window.currentQuarterKey();
        String currentQuarterStartKey = window.currentQuarterStartMonthKey();

        // Keys of the last QUIET_QUARTERS full quarters, for the "gone quiet" badge.
        Set<String> recentQuarterKeys = new java.util.HashSet<>();
        for (int i = 1; i <= QUIET_QUARTERS; i++) {
            recentQuarterKeys.add(quarterKeyOf(window.currentQuarterStart().minusMonths(3L * i)));
        }

        // Per segment: quarter aggregates and per-client aggregates.
        Map<String, SegmentAccumulator> accumulators = new LinkedHashMap<>();
        for (String segment : SEGMENT_ORDER) accumulators.put(segment, new SegmentAccumulator());

        for (RevenueRow row : revenueRows) {
            SegmentAccumulator acc = accumulators.get(normalizeSegment(row.segment()));
            String quarterKey = quarterKeyForMonthKey(row.monthKey());
            boolean toDate = row.monthKey().compareTo(currentQuarterStartKey) >= 0;
            if (toDate) {
                acc.actualToDateByQuarter.merge(quarterKey, row.revenueDkk(), Double::sum);
            } else {
                acc.actualByQuarter.merge(quarterKey, row.revenueDkk(), Double::sum);
            }
            ClientAccumulator client = acc.client(row.clientUuid(), row.clientName());
            client.windowRevenue += row.revenueDkk();
            client.quarterRevenue.merge(quarterKey, row.revenueDkk(), Double::sum);
        }

        for (BudgetRow row : budgetRows) {
            SegmentAccumulator acc = accumulators.get(normalizeSegment(row.segment()));
            String quarterKey = quarterKeyForMonthKey(row.monthKey());
            acc.budgetByQuarter.merge(quarterKey, row.budgetDkk(), Double::sum);
            if (row.monthKey().compareTo(currentQuarterStartKey) >= 0) {
                acc.client(row.clientUuid(), row.clientName()).forecastBudget += row.budgetDkk();
            }
        }

        for (PipelineRow row : pipelineRows) {
            SegmentAccumulator acc = accumulators.get(normalizeSegment(row.segment()));
            acc.pipelineByQuarter.merge(quarterKeyForMonthKey(row.monthKey()), row.pipelineDkk(), Double::sum);
        }

        String actualStartKey = window.actualStartMonthKey();
        String latestFullQuarterKey = window.latestFullQuarterKey();

        List<IndustryTrendSegmentDTO> industries = new ArrayList<>(SEGMENT_ORDER.size());
        for (String segment : SEGMENT_ORDER) {
            SegmentAccumulator acc = accumulators.get(segment);

            List<IndustryTrendPointDTO> series = new ArrayList<>(quarters.size());
            for (IndustryTrendQuarterDTO quarter : quarters) {
                boolean actual = IndustryTrendQuarterDTO.PHASE_ACTUAL.equals(quarter.phase());
                boolean current = quarter.quarterKey().equals(currentQuarterKey);
                series.add(new IndustryTrendPointDTO(
                        quarter.quarterKey(),
                        actual ? acc.actualByQuarter.getOrDefault(quarter.quarterKey(), 0d) : null,
                        current ? acc.actualToDateByQuarter.getOrDefault(quarter.quarterKey(), 0d) : null,
                        acc.budgetByQuarter.getOrDefault(quarter.quarterKey(), 0d),
                        actual ? null : acc.pipelineByQuarter.getOrDefault(quarter.quarterKey(), 0d)));
            }

            double segmentWindowTotal = acc.clients.values().stream()
                    .mapToDouble(c -> c.windowRevenue).sum();

            List<IndustryTrendClientDTO> clients = new ArrayList<>();
            for (ClientAccumulator client : acc.clients.values()) {
                if (client.windowRevenue == 0d && client.forecastBudget == 0d) continue;
                boolean isNew = client.uuid != null
                        && firstInvoiceMonthByClient.containsKey(client.uuid)
                        && firstInvoiceMonthByClient.get(client.uuid).compareTo(actualStartKey) >= 0;
                boolean isQuiet = client.windowRevenue != 0d
                        && recentQuarterKeys.stream().noneMatch(q ->
                                client.quarterRevenue.getOrDefault(q, 0d) != 0d);
                double share = segmentWindowTotal != 0d
                        ? client.windowRevenue / segmentWindowTotal * 100d
                        : 0d;
                Map<String, Double> quarterRevenue = new TreeMap<>();
                client.quarterRevenue.forEach((q, v) -> {
                    if (v != 0d) quarterRevenue.put(q, v);
                });
                clients.add(new IndustryTrendClientDTO(
                        client.uuid,
                        client.name,
                        client.windowRevenue,
                        client.quarterRevenue.getOrDefault(latestFullQuarterKey, 0d),
                        share,
                        client.forecastBudget,
                        isNew,
                        isQuiet,
                        quarterRevenue));
            }
            clients.sort(Comparator
                    .comparingInt((IndustryTrendClientDTO c) -> c.windowRevenueDkk() != 0d ? 0 : 1)
                    .thenComparing(Comparator.comparingDouble(IndustryTrendClientDTO::windowRevenueDkk).reversed())
                    .thenComparing(Comparator.comparingDouble(IndustryTrendClientDTO::forecastBudgetDkk).reversed()));

            industries.add(new IndustryTrendSegmentDTO(
                    segment,
                    SEGMENT_LABELS.getOrDefault(segment, segment),
                    series,
                    clients));
        }

        return new IndustryRevenueTrendDTO(quarters, industries, latestFullQuarterKey, currentQuarterKey);
    }

    private static final class SegmentAccumulator {
        final Map<String, Double> actualByQuarter = new HashMap<>();
        final Map<String, Double> actualToDateByQuarter = new HashMap<>();
        final Map<String, Double> budgetByQuarter = new HashMap<>();
        final Map<String, Double> pipelineByQuarter = new HashMap<>();
        final Map<String, ClientAccumulator> clients = new LinkedHashMap<>();

        ClientAccumulator client(String uuid, String name) {
            String key = uuid != null ? uuid : "__unattributed__";
            String displayName = uuid == null ? UNATTRIBUTED_CLIENT_NAME
                    : (name != null ? name : "(Unknown client)");
            return clients.computeIfAbsent(key, k -> new ClientAccumulator(uuid, displayName));
        }
    }

    private static final class ClientAccumulator {
        final String uuid;
        final String name;
        double windowRevenue;
        double forecastBudget;
        final Map<String, Double> quarterRevenue = new HashMap<>();

        ClientAccumulator(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }
}
