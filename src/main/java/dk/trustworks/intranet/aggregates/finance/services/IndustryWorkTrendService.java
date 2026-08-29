package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementItemDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementPointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryEngagementTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRateClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRatePointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRateSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryRateTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryServiceLinePointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryServiceLineSegmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryServiceLineTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.IndustryTrendQuarterDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.ServiceLineMetaDTO;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.SEGMENT_LABELS;
import static dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.SEGMENT_ORDER;
import static dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.normalizeSegment;
import static dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.quarterKeyOf;
import static dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.quarterNumber;
import static dk.trustworks.intranet.aggregates.finance.services.IndustryRevenueTrendService.quarterStart;

/**
 * Services behind the work-derived charts on the Industries tab:
 *
 * <ul>
 *   <li>{@code GET /clients/cxo/industry-rate-trend} — hours-weighted average
 *       billable rate per industry per quarter, from the {@code work_full_optimized}
 *       view (registered work priced at the contract-consultant rate in effect on
 *       the registration date).</li>
 *   <li>{@code GET /clients/cxo/industry-engagement-trend} — average
 *       consultant×client engagement length per industry per quarter, derived
 *       from registered work rather than contract dates. The full {@code work}
 *       history is used (the work_full view clips at 2021-07-01, which would
 *       left-censor long engagements) with the same contract join rule to decide
 *       billability.</li>
 *   <li>{@code GET /clients/cxo/industry-service-line-trend} — service-line
 *       penetration per industry per quarter, from
 *       {@code fact_project_financials_mat} (same source as the Client &amp;
 *       Portfolio tab's Service Line Penetration chart).</li>
 * </ul>
 *
 * <p>All three cover the trailing 12 full calendar quarters and exclude the
 * internal Trustworks client. Segment codes and labels match
 * {@link IndustryRevenueTrendService}.</p>
 *
 * <h2>Engagement thresholds (derived from production data, Aug 2026)</h2>
 * <ul>
 *   <li><b>Active month = ≥ {@value #ACTIVE_MONTH_MIN_HOURS} billable hours.</b>
 *       Consultant×client months below 8 hours are hand-overs, status meetings
 *       and stray corrections — together they carry &lt; 0.5% of all billable
 *       hours, so they should not start or extend an engagement.</li>
 *   <li><b>Bridge up to {@value #BRIDGE_MONTHS} silent months.</b> 96% of
 *       consecutive active-month transitions have no gap. Single-month gaps
 *       cluster in July/August and December/January (Danish vacation) and
 *       two-month gaps cover long vacation plus short sick leave or bench time.
 *       Silences of three months or more form a clearly separated long tail and
 *       mark a genuine engagement stop (a re-engagement later starts a new
 *       episode).</li>
 *   <li><b>Length is measured first→last active month inclusive</b>, so bridged
 *       vacation months count toward the engagement's duration.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class IndustryWorkTrendService {

    /** Per-query timeout, matching the BFF's request budget (same as CxoClientService). */
    private static final int QUERY_TIMEOUT_MS = 15_000;

    /** Internal Trustworks client — excluded from all series. */
    private static final String INTERNAL_CLIENT_UUID = "d58bb00b-4474-4250-84eb-d8f77548ddac";

    /** Quarters shown (trailing full calendar quarters before the current one). */
    static final int ACTUAL_QUARTERS = 12;

    /** Minimum billable hours in a month for a consultant×client pair to count as active. */
    static final double ACTIVE_MONTH_MIN_HOURS = 8d;

    /** Maximum silent months bridged inside one engagement (vacation, sick leave, short bench). */
    static final int BRIDGE_MONTHS = 2;

    static final String UNATTRIBUTED_CLIENT_NAME = "(Unattributed)";

    @Inject
    EntityManager em;

    // =========================================================================
    // Shared window
    // =========================================================================

    /**
     * The charts' time window: the 12 full calendar quarters before the quarter
     * containing the as-of date. Quarter boundaries coincide with Trustworks
     * fiscal-quarter boundaries (fiscal year starts July 1).
     */
    record ActualsWindow(LocalDate windowStart, LocalDate currentQuarterStart, LocalDate asOf) {

        static ActualsWindow of(LocalDate asOf) {
            LocalDate currentQuarterStart = quarterStart(asOf);
            return new ActualsWindow(
                    currentQuarterStart.minusMonths(3L * ACTUAL_QUARTERS),
                    currentQuarterStart,
                    asOf);
        }

        String windowStartMonthKey() { return IndustryRevenueTrendService.monthKey(windowStart); }

        /** Last month of the window, inclusive. */
        String windowEndMonthKey() { return IndustryRevenueTrendService.monthKey(currentQuarterStart.minusMonths(1)); }

        String latestFullQuarterKey() { return quarterKeyOf(currentQuarterStart.minusMonths(3)); }

        /** Ordered x-axis: the 12 trailing full quarters, all ACTUAL. */
        List<IndustryTrendQuarterDTO> quarterMetas() {
            List<IndustryTrendQuarterDTO> metas = new ArrayList<>(ACTUAL_QUARTERS);
            LocalDate cursor = windowStart;
            while (cursor.isBefore(currentQuarterStart)) {
                String fiscalLabel = cursor.getMonthValue() == 7
                        ? String.format("FY%02d/%02d", cursor.getYear() % 100, (cursor.getYear() + 1) % 100)
                        : null;
                metas.add(new IndustryTrendQuarterDTO(
                        quarterKeyOf(cursor),
                        cursor.getYear(),
                        quarterNumber(cursor),
                        IndustryTrendQuarterDTO.PHASE_ACTUAL,
                        fiscalLabel));
                cursor = cursor.plusMonths(3);
            }
            return metas;
        }
    }

    /** Absolute month number (year × 12 + month) for gap arithmetic. */
    static int monthNum(LocalDate date) {
        return date.getYear() * 12 + date.getMonthValue();
    }

    /** {@code YYYYMM} key for an absolute month number. */
    static String monthKeyForMonthNum(int monthNum) {
        int year = (monthNum - 1) / 12;
        int month = ((monthNum - 1) % 12) + 1;
        return String.format("%04d%02d", year, month);
    }

    // =========================================================================
    // Rate trend
    // =========================================================================

    public IndustryRateTrendDTO getIndustryRateTrend(LocalDate asOfDate, Set<String> companyIds) {
        ActualsWindow window = ActualsWindow.of(asOfDate != null ? asOfDate : LocalDate.now());
        List<RateRow> rows = queryRates(window, companyIds);
        return assembleRates(window, rows);
    }

    /** Raw rate row: segment × client × quarter with the billed-value and hour sums. */
    record RateRow(String segment, String clientUuid, String clientName, String quarterKey,
                   double billedValueDkk, double hours) {}

    private List<RateRow> queryRates(ActualsWindow window, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        // work_full_optimized prices each work row at the contract-consultant rate
        // in effect on the registration date. rate > 0 is the billability signal —
        // the legacy work.billable flag has not been maintained since Dec 2023.
        String sql = "SELECT COALESCE(c.segment, 'OTHER') AS segment, " +
                "w.clientuuid AS client_id, c.name AS client_name, " +
                "CONCAT(YEAR(w.registered), '-Q', QUARTER(w.registered)) AS quarter_key, " +
                "SUM(w.workduration * w.rate) AS billed_value, " +
                "SUM(w.workduration) AS hours " +
                "FROM work_full_optimized w " +
                "LEFT JOIN client c ON c.uuid = w.clientuuid " +
                "WHERE w.registered >= :fromDate AND w.registered < :endExclusive " +
                "AND w.rate > 0 AND w.workduration > 0 AND w.type = 'CONSULTANT' " +
                "AND (w.clientuuid IS NULL OR w.clientuuid <> :internalClientId) " +
                (hasCompanies ? "AND w.contract_company_uuid IN (:companyIds) " : "") +
                "GROUP BY COALESCE(c.segment, 'OTHER'), w.clientuuid, c.name, " +
                "YEAR(w.registered), QUARTER(w.registered)";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);
        query.setParameter("fromDate", window.windowStart());
        query.setParameter("endExclusive", window.currentQuarterStart());
        query.setParameter("internalClientId", INTERNAL_CLIENT_UUID);
        if (hasCompanies) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<RateRow> result = new ArrayList<>(rows.size());
        for (Tuple t : rows) {
            result.add(new RateRow(
                    (String) t.get("segment"),
                    (String) t.get("client_id"),
                    (String) t.get("client_name"),
                    t.get("quarter_key").toString(),
                    ((Number) t.get("billed_value")).doubleValue(),
                    ((Number) t.get("hours")).doubleValue()));
        }
        return result;
    }

    static IndustryRateTrendDTO assembleRates(ActualsWindow window, List<RateRow> rows) {
        List<IndustryTrendQuarterDTO> quarters = window.quarterMetas();
        String latestFullQuarterKey = window.latestFullQuarterKey();

        Map<String, RateSegmentAccumulator> accumulators = new LinkedHashMap<>();
        for (String segment : SEGMENT_ORDER) accumulators.put(segment, new RateSegmentAccumulator());

        for (RateRow row : rows) {
            RateSegmentAccumulator acc = accumulators.get(normalizeSegment(row.segment()));
            RateCell cell = acc.quarterCells.computeIfAbsent(row.quarterKey(), k -> new RateCell());
            cell.billedValue += row.billedValueDkk();
            cell.hours += row.hours();
            if (row.hours() > 0d) cell.clientKeys.add(row.clientUuid() != null ? row.clientUuid() : "__unattributed__");

            RateClientAccumulator client = acc.client(row.clientUuid(), row.clientName());
            client.windowBilledValue += row.billedValueDkk();
            client.windowHours += row.hours();
            if (row.quarterKey().equals(latestFullQuarterKey)) {
                client.latestQuarterBilledValue += row.billedValueDkk();
                client.latestQuarterHours += row.hours();
            }
        }

        List<IndustryRateSegmentDTO> industries = new ArrayList<>(SEGMENT_ORDER.size());
        for (String segment : SEGMENT_ORDER) {
            RateSegmentAccumulator acc = accumulators.get(segment);

            List<IndustryRatePointDTO> series = new ArrayList<>(quarters.size());
            for (IndustryTrendQuarterDTO quarter : quarters) {
                RateCell cell = acc.quarterCells.get(quarter.quarterKey());
                boolean hasHours = cell != null && cell.hours > 0d;
                series.add(new IndustryRatePointDTO(
                        quarter.quarterKey(),
                        hasHours ? cell.billedValue / cell.hours : null,
                        hasHours ? cell.hours : 0d,
                        hasHours ? cell.clientKeys.size() : 0));
            }

            List<IndustryRateClientDTO> clients = new ArrayList<>();
            for (RateClientAccumulator client : acc.clients.values()) {
                if (client.windowHours <= 0d) continue;
                clients.add(new IndustryRateClientDTO(
                        client.uuid,
                        client.name,
                        client.windowHours,
                        client.windowBilledValue / client.windowHours,
                        client.latestQuarterHours,
                        client.latestQuarterHours > 0d
                                ? client.latestQuarterBilledValue / client.latestQuarterHours
                                : null));
            }
            clients.sort(Comparator.comparingDouble(IndustryRateClientDTO::windowHours).reversed());

            industries.add(new IndustryRateSegmentDTO(
                    segment,
                    SEGMENT_LABELS.getOrDefault(segment, segment),
                    series,
                    clients));
        }

        return new IndustryRateTrendDTO(quarters, industries, latestFullQuarterKey);
    }

    private static final class RateCell {
        double billedValue;
        double hours;
        final Set<String> clientKeys = new HashSet<>();
    }

    private static final class RateSegmentAccumulator {
        final Map<String, RateCell> quarterCells = new HashMap<>();
        final Map<String, RateClientAccumulator> clients = new LinkedHashMap<>();

        RateClientAccumulator client(String uuid, String name) {
            String key = uuid != null ? uuid : "__unattributed__";
            String displayName = uuid == null ? UNATTRIBUTED_CLIENT_NAME
                    : (name != null ? name : "(Unknown client)");
            return clients.computeIfAbsent(key, k -> new RateClientAccumulator(uuid, displayName));
        }
    }

    private static final class RateClientAccumulator {
        final String uuid;
        final String name;
        double windowBilledValue;
        double windowHours;
        double latestQuarterBilledValue;
        double latestQuarterHours;

        RateClientAccumulator(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    // =========================================================================
    // Engagement trend
    // =========================================================================

    public IndustryEngagementTrendDTO getIndustryEngagementTrend(LocalDate asOfDate, Set<String> companyIds) {
        ActualsWindow window = ActualsWindow.of(asOfDate != null ? asOfDate : LocalDate.now());
        List<ActiveMonthRow> rows = queryActiveMonths(window, companyIds);
        return assembleEngagements(window, rows);
    }

    /** One active consultant×client month (≥ {@link #ACTIVE_MONTH_MIN_HOURS} billable hours). */
    record ActiveMonthRow(String segment, String userUuid, String consultantName,
                          String clientUuid, String clientName, int monthNum) {}

    private List<ActiveMonthRow> queryActiveMonths(ActualsWindow window, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        // Full work history (the work_full view clips at 2021-07-01, which would
        // left-censor long-running engagements) with the same contract join rule:
        // a row counts as billable staffing when a contract-consultant row with a
        // positive rate is in effect on the registration date. EXISTS avoids the
        // row duplication a join against overlapping contract rows would cause.
        // Months in the current partial quarter are included so an engagement that
        // is still running is not reported as ended in the last full quarter.
        String sql = "SELECT COALESCE(c.segment, 'OTHER') AS segment, " +
                "w.useruuid AS user_id, CONCAT(u.firstname, ' ', u.lastname) AS consultant_name, " +
                "p.clientuuid AS client_id, c.name AS client_name, " +
                "(YEAR(w.registered) * 12 + MONTH(w.registered)) AS month_num " +
                "FROM work w " +
                "LEFT JOIN user u ON u.uuid = w.useruuid " +
                "LEFT JOIN task t ON w.taskuuid = t.uuid " +
                "LEFT JOIN project p ON t.projectuuid = p.uuid " +
                "LEFT JOIN client c ON c.uuid = p.clientuuid " +
                "WHERE w.workduration > 0 " +
                "AND w.registered <= :asOfDate " +
                "AND p.clientuuid IS NOT NULL AND p.clientuuid <> :internalClientId " +
                "AND EXISTS (SELECT 1 FROM contract_project cp " +
                "            JOIN contract_consultants cc ON cp.contractuuid = cc.contractuuid " +
                (hasCompanies
                        ? "        JOIN contracts ct ON cc.contractuuid = ct.uuid "
                        : "") +
                "            WHERE cp.projectuuid = p.uuid " +
                "            AND cc.useruuid = IF(w.workas IS NOT NULL, w.workas, w.useruuid) " +
                "            AND cc.activefrom <= w.registered AND cc.activeto >= w.registered " +
                "            AND cc.rate > 0 " +
                (hasCompanies ? "AND ct.companyuuid IN (:companyIds) " : "") +
                ") " +
                "GROUP BY segment, w.useruuid, consultant_name, p.clientuuid, c.name, month_num " +
                "HAVING SUM(w.workduration) >= :minHours";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);
        query.setParameter("asOfDate", window.asOf());
        query.setParameter("internalClientId", INTERNAL_CLIENT_UUID);
        query.setParameter("minHours", ACTIVE_MONTH_MIN_HOURS);
        if (hasCompanies) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<ActiveMonthRow> result = new ArrayList<>(rows.size());
        for (Tuple t : rows) {
            result.add(new ActiveMonthRow(
                    (String) t.get("segment"),
                    (String) t.get("user_id"),
                    (String) t.get("consultant_name"),
                    (String) t.get("client_id"),
                    (String) t.get("client_name"),
                    ((Number) t.get("month_num")).intValue()));
        }
        return result;
    }

    /** One detected engagement episode of a consultant at a client. */
    record Episode(String segment, String consultantName, String clientUuid, String clientName,
                   int firstMonthNum, int lastMonthNum) {

        int runningMonthsThrough(int monthNum) {
            return Math.min(lastMonthNum, monthNum) - firstMonthNum + 1;
        }
    }

    /**
     * Splits each consultant×client month series into engagement episodes:
     * a silence longer than {@link #BRIDGE_MONTHS} months starts a new episode.
     */
    static List<Episode> detectEpisodes(List<ActiveMonthRow> rows) {
        Map<String, List<ActiveMonthRow>> byPair = new LinkedHashMap<>();
        for (ActiveMonthRow row : rows) {
            byPair.computeIfAbsent(row.userUuid() + '|' + row.clientUuid(), k -> new ArrayList<>()).add(row);
        }

        List<Episode> episodes = new ArrayList<>();
        for (List<ActiveMonthRow> months : byPair.values()) {
            months.sort(Comparator.comparingInt(ActiveMonthRow::monthNum));
            ActiveMonthRow meta = months.get(0);
            int first = meta.monthNum();
            int last = meta.monthNum();
            for (int i = 1; i < months.size(); i++) {
                int monthNum = months.get(i).monthNum();
                if (monthNum == last) continue;
                if (monthNum - last > BRIDGE_MONTHS + 1) {
                    episodes.add(new Episode(meta.segment(), meta.consultantName(),
                            meta.clientUuid(), meta.clientName(), first, last));
                    first = monthNum;
                }
                last = monthNum;
            }
            episodes.add(new Episode(meta.segment(), meta.consultantName(),
                    meta.clientUuid(), meta.clientName(), first, last));
        }
        return episodes;
    }

    static IndustryEngagementTrendDTO assembleEngagements(ActualsWindow window, List<ActiveMonthRow> rows) {
        List<IndustryTrendQuarterDTO> quarters = window.quarterMetas();
        String latestFullQuarterKey = window.latestFullQuarterKey();
        int latestFullQuarterStartNum = monthNum(window.currentQuarterStart().minusMonths(3));
        // Data exists only through the as-of month — silence after an episode can
        // only be judged against months that have actually happened.
        int lastDataMonthNum = monthNum(window.asOf());

        List<Episode> episodes = detectEpisodes(rows);

        Map<String, List<IndustryEngagementPointDTO>> seriesBySegment = new LinkedHashMap<>();
        Map<String, List<IndustryEngagementItemDTO>> itemsBySegment = new LinkedHashMap<>();
        for (String segment : SEGMENT_ORDER) {
            seriesBySegment.put(segment, new ArrayList<>(quarters.size()));
            itemsBySegment.put(segment, new ArrayList<>());
        }

        for (IndustryTrendQuarterDTO quarter : quarters) {
            LocalDate qStart = LocalDate.of(quarter.year(), (quarter.quarterNumber() - 1) * 3 + 1, 1);
            int qs = monthNum(qStart);
            int qe = qs + 2;

            Map<String, EngagementCell> cells = new HashMap<>();
            for (String segment : SEGMENT_ORDER) cells.put(segment, new EngagementCell());

            for (Episode episode : episodes) {
                if (episode.firstMonthNum() > qe || episode.lastMonthNum() < qs) continue;
                EngagementCell cell = cells.get(normalizeSegment(episode.segment()));
                cell.active++;
                cell.runningMonthsSum += episode.runningMonthsThrough(qe);
                if (episode.firstMonthNum() >= qs) cell.started++;
                // Only call an episode ended once enough silent months have passed
                // by the data horizon for the bridge rule to have ruled out a resume.
                if (episode.lastMonthNum() <= qe
                        && episode.lastMonthNum() + BRIDGE_MONTHS + 1 <= lastDataMonthNum) {
                    cell.ended++;
                }
            }

            for (String segment : SEGMENT_ORDER) {
                EngagementCell cell = cells.get(segment);
                seriesBySegment.get(segment).add(new IndustryEngagementPointDTO(
                        quarter.quarterKey(),
                        cell.active > 0 ? cell.runningMonthsSum / cell.active : null,
                        cell.active,
                        cell.started,
                        cell.ended));
            }
        }

        // Drill-down: engagements with active work in or after the latest full quarter.
        for (Episode episode : episodes) {
            if (episode.lastMonthNum() < latestFullQuarterStartNum) continue;
            itemsBySegment.get(normalizeSegment(episode.segment())).add(new IndustryEngagementItemDTO(
                    episode.consultantName() != null ? episode.consultantName() : "(Unknown consultant)",
                    episode.clientUuid(),
                    episode.clientName() != null ? episode.clientName() : "(Unknown client)",
                    monthKeyForMonthNum(episode.firstMonthNum()),
                    episode.lastMonthNum() - episode.firstMonthNum() + 1,
                    episode.lastMonthNum() + BRIDGE_MONTHS + 1 > lastDataMonthNum));
        }

        List<IndustryEngagementSegmentDTO> industries = new ArrayList<>(SEGMENT_ORDER.size());
        for (String segment : SEGMENT_ORDER) {
            List<IndustryEngagementItemDTO> items = itemsBySegment.get(segment);
            items.sort(Comparator.comparingInt(IndustryEngagementItemDTO::runningMonths).reversed());
            industries.add(new IndustryEngagementSegmentDTO(
                    segment,
                    SEGMENT_LABELS.getOrDefault(segment, segment),
                    seriesBySegment.get(segment),
                    items));
        }

        return new IndustryEngagementTrendDTO(
                quarters, industries, latestFullQuarterKey, ACTIVE_MONTH_MIN_HOURS, BRIDGE_MONTHS);
    }

    private static final class EngagementCell {
        int active;
        int started;
        int ended;
        double runningMonthsSum;
    }

    // =========================================================================
    // Service-line trend
    // =========================================================================

    public IndustryServiceLineTrendDTO getIndustryServiceLineTrend(LocalDate asOfDate, Set<String> companyIds) {
        ActualsWindow window = ActualsWindow.of(asOfDate != null ? asOfDate : LocalDate.now());
        List<ServiceLineRow> rows = queryServiceLines(window, companyIds);
        Map<String, String> practiceNames = queryPracticeNames();
        return assembleServiceLines(window, rows, practiceNames);
    }

    /** One distinct segment × service line × client × quarter occurrence. */
    record ServiceLineRow(String segment, String serviceLineId, String clientUuid,
                          String clientName, String quarterKey) {}

    private List<ServiceLineRow> queryServiceLines(ActualsWindow window, Set<String> companyIds) {
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        // Same source as the Client & Portfolio tab's Service Line Penetration
        // chart. Distinct occurrences are enough — the counts are client counts,
        // so the V118 duplicate-amount issue does not apply.
        String sql = "SELECT DISTINCT COALESCE(c.segment, 'OTHER') AS segment, " +
                "f.service_line_id AS service_line_id, " +
                "f.client_id AS client_id, c.name AS client_name, " +
                "CONCAT(SUBSTRING(f.month_key, 1, 4), '-Q', " +
                "       FLOOR((CAST(SUBSTRING(f.month_key, 5, 2) AS UNSIGNED) - 1) / 3) + 1) AS quarter_key " +
                "FROM fact_project_financials_mat f " +
                "LEFT JOIN client c ON c.uuid = f.client_id " +
                "WHERE f.month_key >= :fromKey AND f.month_key <= :toKey " +
                "AND f.client_id IS NOT NULL AND f.client_id <> :internalClientId " +
                "AND f.service_line_id IS NOT NULL " +
                "AND f.recognized_revenue_dkk > 0 " +
                (hasCompanies ? "AND f.companyuuid IN (:companyIds) " : "");

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);
        query.setParameter("fromKey", window.windowStartMonthKey());
        query.setParameter("toKey", window.windowEndMonthKey());
        query.setParameter("internalClientId", INTERNAL_CLIENT_UUID);
        if (hasCompanies) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        List<ServiceLineRow> result = new ArrayList<>(rows.size());
        for (Tuple t : rows) {
            result.add(new ServiceLineRow(
                    (String) t.get("segment"),
                    (String) t.get("service_line_id"),
                    (String) t.get("client_id"),
                    (String) t.get("client_name"),
                    t.get("quarter_key").toString()));
        }
        return result;
    }

    /** Practice display names by code, for labeling service lines. */
    private Map<String, String> queryPracticeNames() {
        Query query = em.createNativeQuery("SELECT code, name FROM practice", Tuple.class);
        query.setHint("javax.persistence.query.timeout", QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();
        Map<String, String> result = new HashMap<>(rows.size());
        for (Tuple t : rows) {
            result.put((String) t.get("code"), (String) t.get("name"));
        }
        return result;
    }

    static IndustryServiceLineTrendDTO assembleServiceLines(ActualsWindow window,
                                                            List<ServiceLineRow> rows,
                                                            Map<String, String> practiceNames) {
        List<IndustryTrendQuarterDTO> quarters = window.quarterMetas();
        String latestFullQuarterKey = window.latestFullQuarterKey();

        Set<String> serviceLineCodes = new TreeSet<>();
        // segment → quarter → service line → distinct clients
        Map<String, Map<String, Map<String, Set<String>>>> clientsBySegment = new LinkedHashMap<>();
        // segment → quarter → client → distinct service lines
        Map<String, Map<String, Map<String, Set<String>>>> linesByClient = new LinkedHashMap<>();
        // segment → service line → client names in the latest full quarter
        Map<String, Map<String, Set<String>>> latestClients = new LinkedHashMap<>();
        for (String segment : SEGMENT_ORDER) {
            clientsBySegment.put(segment, new HashMap<>());
            linesByClient.put(segment, new HashMap<>());
            latestClients.put(segment, new TreeMap<>());
        }

        for (ServiceLineRow row : rows) {
            String segment = normalizeSegment(row.segment());
            serviceLineCodes.add(row.serviceLineId());
            clientsBySegment.get(segment)
                    .computeIfAbsent(row.quarterKey(), k -> new HashMap<>())
                    .computeIfAbsent(row.serviceLineId(), k -> new HashSet<>())
                    .add(row.clientUuid());
            linesByClient.get(segment)
                    .computeIfAbsent(row.quarterKey(), k -> new HashMap<>())
                    .computeIfAbsent(row.clientUuid(), k -> new HashSet<>())
                    .add(row.serviceLineId());
            if (row.quarterKey().equals(latestFullQuarterKey)) {
                latestClients.get(segment)
                        .computeIfAbsent(row.serviceLineId(), k -> new TreeSet<>())
                        .add(row.clientName() != null ? row.clientName() : "(Unknown client)");
            }
        }

        List<ServiceLineMetaDTO> serviceLines = new ArrayList<>(serviceLineCodes.size());
        for (String code : serviceLineCodes) {
            serviceLines.add(new ServiceLineMetaDTO(code, practiceNames.getOrDefault(code, code)));
        }

        List<IndustryServiceLineSegmentDTO> industries = new ArrayList<>(SEGMENT_ORDER.size());
        for (String segment : SEGMENT_ORDER) {
            List<IndustryServiceLinePointDTO> series = new ArrayList<>(quarters.size());
            for (IndustryTrendQuarterDTO quarter : quarters) {
                Map<String, Set<String>> bySl = clientsBySegment.get(segment)
                        .getOrDefault(quarter.quarterKey(), Map.of());
                Map<String, Set<String>> byClient = linesByClient.get(segment)
                        .getOrDefault(quarter.quarterKey(), Map.of());

                Map<String, Integer> counts = new TreeMap<>();
                bySl.forEach((sl, clients) -> counts.put(sl, clients.size()));

                int activeClients = byClient.size();
                Double avgLines = activeClients > 0
                        ? byClient.values().stream().mapToInt(Set::size).sum() / (double) activeClients
                        : null;

                series.add(new IndustryServiceLinePointDTO(
                        quarter.quarterKey(), activeClients, counts, avgLines));
            }

            Map<String, List<String>> latest = new TreeMap<>();
            latestClients.get(segment).forEach((sl, names) -> latest.put(sl, new ArrayList<>(names)));

            industries.add(new IndustryServiceLineSegmentDTO(
                    segment,
                    SEGMENT_LABELS.getOrDefault(segment, segment),
                    series,
                    latest));
        }

        return new IndustryServiceLineTrendDTO(quarters, serviceLines, industries, latestFullQuarterKey);
    }
}
