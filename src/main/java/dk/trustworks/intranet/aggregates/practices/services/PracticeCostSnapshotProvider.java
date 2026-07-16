package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Canonical read seam for published practice salary, OPEX and FTE snapshots.
 *
 * <p>The legacy operating-cost endpoint, cost publisher, revenue publisher and contribution
 * reader all consume this provider. The immutable publication is always readable by the cost and
 * revenue builders, even while the legacy endpoint is suppressed. Only the legacy adapter applies
 * {@code legacy_cost_serving_enabled}; this separation is required so a forward repair can build
 * and reconcile a replacement while every financial surface remains dark.</p>
 */
@JBossLog
@ApplicationScoped
public class PracticeCostSnapshotProvider {

    static final String LEGACY_SERVING_SQL = """
            SELECT legacy_cost_serving_enabled
            FROM practice_contribution_publication_control
            WHERE control_id = 1
            """;

    static final String SERVING_CONTROL_SQL = """
            SELECT c.legacy_cost_serving_enabled,
                   p.refresh_state, p.active_refresh_token, p.generation_at, p.published_at,
                   p.practice_basis_generation_id, p.certified_cost_basis_request_id,
                   p.certified_cost_basis_request_vector, p.cost_content_fingerprint,
                   p.opex_row_count, p.fte_row_count, p.completeness_row_count,
                   b.status, b.coverage_start_date, b.source_fingerprint,
                   b.capacity_source_fingerprint, b.dependency_manifest_fingerprint,
                   (SELECT COUNT(*) FROM fact_practice_cost_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(*) FROM fact_practice_fte_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(*) FROM fact_practice_cost_completeness_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_cost_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_fte_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_cost_completeness_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT MIN(content_fingerprint) FROM fact_practice_cost_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT MIN(content_fingerprint) FROM fact_practice_fte_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT MIN(content_fingerprint) FROM fact_practice_cost_completeness_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   p.booked_available, p.booked_reason, p.booked_anchor_month,
                   p.booked_current_start_month, p.booked_current_end_month,
                   p.booked_prior_start_month, p.booked_prior_end_month,
                   p.booked_plus_draft_available, p.booked_plus_draft_reason,
                   p.booked_plus_draft_anchor_month, p.booked_plus_draft_current_start_month,
                   p.booked_plus_draft_current_end_month, p.booked_plus_draft_prior_start_month,
                   p.booked_plus_draft_prior_end_month,
                   p.publication_version
            FROM practice_contribution_publication_control c
            JOIN practice_operating_cost_publication p ON p.publication_id=1
            LEFT JOIN practice_basis_generation b ON b.generation_id=p.practice_basis_generation_id
            WHERE c.control_id = 1
            """;

    @Inject
    EntityManager em;

    @Inject
    PracticeCostSnapshotLoader snapshotLoader;

    public Snapshot getSnapshot(CostSource requestedCostSource) {
        return getSnapshot(requestedCostSource, CxoSqlSupport.CXO_QUERY_TIMEOUT_MS);
    }

    /**
     * Reads the canonical snapshot with a caller-bounded per-query timeout.
     *
     * <p>The contribution endpoint uses this overload so every cost query shares its fixed
     * request deadline. Other callers retain the established CXO timeout through
     * {@link #getSnapshot(CostSource)}.</p>
     */
    public Snapshot getSnapshot(CostSource requestedCostSource, int queryTimeoutMs) {
        int boundedQueryTimeoutMs = boundedQueryTimeout(queryTimeoutMs);
        return getSnapshot(requestedCostSource, () -> boundedQueryTimeoutMs);
    }

    /**
     * Reads the canonical snapshot while re-sampling the caller's remaining request budget
     * immediately before every database query.
     */
    public Snapshot getSnapshot(CostSource requestedCostSource, IntSupplier queryTimeoutMs) {
        Objects.requireNonNull(queryTimeoutMs, "queryTimeoutMs");
        CostSource costSource = requestedCostSource == null ? CostSource.BOOKED : requestedCostSource;
        IntegrityPointer before = loadIntegrityPointer(queryTimeoutMs);
        CanonicalSnapshot canonical = before.basisGenerationId() == null
                ? snapshotLoader.readPublishedSnapshot(costSource, queryTimeoutMs)
                : snapshotLoader.readPublishedCanonicalSnapshot(
                        costSource, before.basisGenerationId(), before.generationAt(), before.coverageStart(),
                        before.window(costSource), queryTimeoutMs);
        IntegrityPointer after = loadIntegrityPointer(queryTimeoutMs);
        if (!before.equals(after)) {
            log.warn("practice cost publication changed during canonical snapshot read");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        return new Snapshot(canonical, before.servingEnabled());
    }

    /**
     * Legacy endpoint view. The canonical publication is still double-read and certified while
     * dark, but no financial value crosses this adapter when legacy serving is disabled.
     */
    Snapshot getLegacySnapshot(CostSource requestedCostSource) {
        CostSource costSource = requestedCostSource == null ? CostSource.BOOKED : requestedCostSource;
        if (!legacyServingEnabled()) {
            return new Snapshot(unavailableCanonical(costSource, "COST_SERVING_DISABLED"), false);
        }
        Snapshot snapshot = getSnapshot(costSource);
        if (snapshot.servingEnabled()) return snapshot;
        return new Snapshot(unavailableCanonical(costSource, "COST_SERVING_DISABLED"), false);
    }

    private boolean legacyServingEnabled() {
        Query query = em.createNativeQuery(LEGACY_SERVING_SQL);
        query.setHint("jakarta.persistence.query.timeout", CxoSqlSupport.CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object> values = query.getResultList();
        if (values.size() != 1) {
            log.warn("practice cost legacy serving control is missing or duplicated");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        Object value = values.getFirst();
        return bool(value instanceof Object[] row ? row[0] : value);
    }

    CanonicalSnapshot loadCandidateSnapshot(CostSource source, String basisGenerationId,
                                            Instant generationAt, LocalDate coverageStart) {
        return snapshotLoader.readCandidateCanonicalSnapshot(
                source, basisGenerationId, generationAt, coverageStart);
    }

    static CanonicalWindow windowOf(CanonicalSnapshot snapshot) {
        if (!snapshot.windowAvailable()) {
            return new CanonicalWindow(false, snapshot.windowReason(), null, null, null, null, null);
        }
        return new CanonicalWindow(true, null,
                monthDate(snapshot.reportingThroughMonthKey()),
                monthDate(snapshot.currentPeriodStartMonthKey()),
                monthDate(snapshot.currentPeriodEndMonthKey()),
                monthDate(snapshot.priorPeriodStartMonthKey()),
                monthDate(snapshot.priorPeriodEndMonthKey()));
    }

    private static LocalDate monthDate(String key) {
        return java.time.YearMonth.parse(key, java.time.format.DateTimeFormatter.ofPattern("yyyyMM")).atDay(1);
    }

    private IntegrityPointer loadIntegrityPointer(IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery(SERVING_CONTROL_SQL);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked")
        List<Object> values = query.getResultList();
        if (values.size() != 1) {
            log.warn("practice cost serving control is missing or duplicated");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        Object[] row = (Object[]) values.getFirst();
        boolean enabled = bool(row[0]);
        if (row[5] == null) {
            if (!"READY".equals(text(row[1])) || row[2] != null || row[3] == null || row[4] == null) {
                throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
            }
            return new IntegrityPointer(enabled, null, toInstant(row[3]), toInstant(row[4]), null,
                    hashPointer(row[1], row[3], row[4], row[9], row[10], row[11]), null, null,
                    null, null, null, integer(row[40]));
        }
        String costFingerprint = text(row[8]);
        String candidateFingerprint = text(row[23]);
        boolean coherent = "READY".equals(text(row[1])) && row[2] == null
                && row[3] != null && row[4] != null && row[5] != null
                && row[6] != null && row[7] != null && costFingerprint != null
                && "READY".equals(text(row[12])) && row[13] != null
                && row[14] != null && row[15] != null && row[16] != null
                && number(row[9]).longValue() == number(row[17]).longValue()
                && number(row[10]).longValue() == number(row[18]).longValue()
                && number(row[11]).longValue() == number(row[19]).longValue()
                && number(row[17]).longValue() > 0 && number(row[18]).longValue() > 0
                && number(row[19]).longValue() > 0
                && number(row[20]).intValue() == 1 && number(row[21]).intValue() == 1
                && number(row[22]).intValue() == 1 && candidateFingerprint != null
                && candidateFingerprint.equals(text(row[24])) && candidateFingerprint.equals(text(row[25]))
                && costFingerprint.equals(fingerprint(row[14], row[15], row[16], candidateFingerprint));
        if (!coherent) {
            log.warn("practice cost immutable generation failed integrity certification");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        CanonicalWindow booked = window(row, 26);
        CanonicalWindow bookedPlusDraft = window(row, 33);
        return new IntegrityPointer(enabled, text(row[5]), toInstant(row[3]), toInstant(row[4]),
                toDate(row[13]), costFingerprint, candidateFingerprint, text(row[6]), text(row[7]),
                booked, bookedPlusDraft, integer(row[40]));
    }

    private static int boundedQueryTimeout(int queryTimeoutMs) {
        if (queryTimeoutMs < 1 || queryTimeoutMs > CxoSqlSupport.CXO_QUERY_TIMEOUT_MS) {
            throw new IllegalArgumentException("query timeout is outside the canonical CXO bound");
        }
        return queryTimeoutMs;
    }

    private static int remainingQueryTimeout(IntSupplier queryTimeoutMs) {
        return boundedQueryTimeout(queryTimeoutMs.getAsInt());
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool
                : value instanceof Number number ? number.intValue() != 0
                : Boolean.parseBoolean(String.valueOf(value));
    }
    private static Number number(Object value) { return (Number) value; }
    private static BigInteger integer(Object value) {
        if (value instanceof BigInteger integer) return integer;
        return new BigInteger(String.valueOf(value));
    }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        return ((Timestamp) value).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    private static LocalDate toDate(Object value) {
        return value instanceof LocalDate date ? date : ((java.sql.Date) value).toLocalDate();
    }
    private static CanonicalWindow window(Object[] row, int offset) {
        boolean available = bool(row[offset]);
        CanonicalWindow result = new CanonicalWindow(available, text(row[offset + 1]),
                toDateOrNull(row[offset + 2]), toDateOrNull(row[offset + 3]),
                toDateOrNull(row[offset + 4]), toDateOrNull(row[offset + 5]),
                toDateOrNull(row[offset + 6]));
        if (!result.validShape()) {
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        return result;
    }
    private static LocalDate toDateOrNull(Object value) { return value == null ? null : toDate(value); }
    private static String hashPointer(Object... values) {
        return java.util.Arrays.deepToString(values);
    }
    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static CanonicalSnapshot unavailableCanonical(CostSource source, String reason) {
        List<CanonicalPractice> rows = PracticeCostSnapshotLoader.PRACTICES.stream()
                .map(CanonicalPractice::unavailable)
                .toList();
        return new CanonicalSnapshot(source.name(), false, reason,
                null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                false, false, "UNAVAILABLE",
                false, false, "UNAVAILABLE",
                "UNAVAILABLE", false,
                "EFFECTIVE_DATED_PRACTICE", null,
                "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW".equals(reason)
                        ? "No complete 12-month operating-cost window exists in the certified search horizon."
                        : "Operating-cost values are withheld while the cost-integrity serving gate is disabled.",
                0, 0, rows);
    }

    public record Snapshot(CanonicalSnapshot canonical, boolean servingEnabled) {
        public Snapshot {
            if (canonical == null) throw new IllegalArgumentException("canonical snapshot is required");
        }
        public Snapshot(PracticeOperatingCostResponseDTO response, boolean servingEnabled) {
            this(CanonicalSnapshot.fromLegacy(response), servingEnabled);
        }
        public PracticeOperatingCostResponseDTO response() {
            return canonical.toLegacyResponse();
        }
    }

    /** Exact canonical values. Only {@link #toLegacyResponse()} crosses the legacy Double boundary. */
    public record CanonicalSnapshot(
            String costSource, boolean windowAvailable, String windowReason,
            String reportingThroughMonthKey, String currentPeriodStartMonthKey,
            String currentPeriodEndMonthKey, String priorPeriodStartMonthKey,
            String priorPeriodEndMonthKey, Instant sourceRefreshedAt,
            int currentSalaryMonthCount, int currentOpexMonthCount, int currentFteMonthCount,
            int priorSalaryMonthCount, int priorOpexMonthCount, int priorFteMonthCount,
            int currentExpectedSalaryCellCount, int currentActualSalaryCellCount,
            int currentCoveredSalaryCellCount, int currentMissingSalaryCellCount,
            int currentUnexpectedSalaryCellCount, int priorExpectedSalaryCellCount,
            int priorActualSalaryCellCount, int priorCoveredSalaryCellCount,
            int priorMissingSalaryCellCount, int priorUnexpectedSalaryCellCount,
            int currentExpectedFteCellCount, int currentCoveredFteCellCount,
            int currentMissingFteCellCount, int priorExpectedFteCellCount,
            int priorCoveredFteCellCount, int priorMissingFteCellCount,
            boolean currentCostComplete, boolean currentFteComplete, String currentCompletenessStatus,
            boolean priorCostComplete, boolean priorFteComplete, String priorCompletenessStatus,
            String completenessStatus, boolean complete, String practiceAttribution,
            LocalDate practiceAttributionCoverageStartDate, String attributionNote,
            int currentCostMonthEndFallbackEmployeeMonthCount,
            int priorCostMonthEndFallbackEmployeeMonthCount,
            List<CanonicalPractice> practices) {
        public CanonicalSnapshot {
            practices = List.copyOf(practices);
        }

        static CanonicalSnapshot fromLegacy(PracticeOperatingCostResponseDTO response) {
            if (response == null) throw new IllegalArgumentException("response is required");
            List<CanonicalPractice> rows = response.practices().stream()
                    .map(CanonicalPractice::fromLegacy)
                    .map(row -> row.withFteCoverage(
                            12, response.currentFteComplete() ? 12 : 0,
                            response.currentFteComplete() ? 0 : 12,
                            12, response.priorFteComplete() ? 12 : 0,
                            response.priorFteComplete() ? 0 : 12))
                    .toList();
            return new CanonicalSnapshot(response.costSource(), response.reportingThroughMonthKey() != null,
                    response.reportingThroughMonthKey() == null ? "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW" : null,
                    response.reportingThroughMonthKey(), response.currentPeriodStartMonthKey(),
                    response.currentPeriodEndMonthKey(), response.priorPeriodStartMonthKey(),
                    response.priorPeriodEndMonthKey(), response.sourceRefreshedAt(),
                    response.currentSalaryMonthCount(), response.currentOpexMonthCount(), response.currentFteMonthCount(),
                    response.priorSalaryMonthCount(), response.priorOpexMonthCount(), response.priorFteMonthCount(),
                    response.currentExpectedSalaryCellCount(), response.currentActualSalaryCellCount(),
                    response.currentCoveredSalaryCellCount(), response.currentMissingSalaryCellCount(),
                    response.currentUnexpectedSalaryCellCount(), response.priorExpectedSalaryCellCount(),
                    response.priorActualSalaryCellCount(), response.priorCoveredSalaryCellCount(),
                    response.priorMissingSalaryCellCount(), response.priorUnexpectedSalaryCellCount(),
                    response.currentExpectedFteCellCount(), response.currentCoveredFteCellCount(),
                    response.currentMissingFteCellCount(), response.priorExpectedFteCellCount(),
                    response.priorCoveredFteCellCount(), response.priorMissingFteCellCount(),
                    response.currentCostComplete(), response.currentFteComplete(), response.currentCompletenessStatus(),
                    response.priorCostComplete(), response.priorFteComplete(), response.priorCompletenessStatus(),
                    response.completenessStatus(), response.complete(), response.practiceAttribution(),
                    response.practiceAttributionCoverageStartDate(), response.attributionNote(), 0, 0, rows);
        }

        PracticeOperatingCostResponseDTO toLegacyResponse() {
            return new PracticeOperatingCostResponseDTO(costSource, reportingThroughMonthKey,
                    currentPeriodStartMonthKey, currentPeriodEndMonthKey,
                    priorPeriodStartMonthKey, priorPeriodEndMonthKey, sourceRefreshedAt,
                    currentSalaryMonthCount, currentOpexMonthCount, currentFteMonthCount,
                    priorSalaryMonthCount, priorOpexMonthCount, priorFteMonthCount,
                    currentExpectedSalaryCellCount, currentActualSalaryCellCount,
                    currentCoveredSalaryCellCount, currentMissingSalaryCellCount,
                    currentUnexpectedSalaryCellCount, priorExpectedSalaryCellCount,
                    priorActualSalaryCellCount, priorCoveredSalaryCellCount,
                    priorMissingSalaryCellCount, priorUnexpectedSalaryCellCount,
                    currentExpectedFteCellCount, currentCoveredFteCellCount, currentMissingFteCellCount,
                    priorExpectedFteCellCount, priorCoveredFteCellCount, priorMissingFteCellCount,
                    currentCostComplete, currentFteComplete, currentCompletenessStatus,
                    priorCostComplete, priorFteComplete, priorCompletenessStatus,
                    completenessStatus, complete, practiceAttribution,
                    practiceAttributionCoverageStartDate, attributionNote,
                    practices.stream().map(CanonicalPractice::toLegacy).toList());
        }
    }

    public record CanonicalPractice(
            String practiceId,
            BigDecimal currentSalaryDkk, BigDecimal currentOpexDkk, BigDecimal currentTotalDkk,
            BigDecimal priorSalaryDkk, BigDecimal priorOpexDkk, BigDecimal priorTotalDkk,
            BigDecimal totalDeltaDkk, BigDecimal totalDeltaPct,
            BigDecimal currentAverageFte, BigDecimal priorAverageFte,
            BigDecimal currentCostPerFteDkk, BigDecimal priorCostPerFteDkk,
            BigDecimal costPerFteDeltaDkk, BigDecimal costPerFteDeltaPct,
            int currentExpectedFteCellCount, int currentCoveredFteCellCount,
            int currentMissingFteCellCount, int priorExpectedFteCellCount,
            int priorCoveredFteCellCount, int priorMissingFteCellCount) {
        static CanonicalPractice unavailable(String practice) {
            return new CanonicalPractice(practice, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    0, 0, 0, 0, 0, 0);
        }
        static CanonicalPractice fromLegacy(PracticeOperatingCostDTO row) {
            return new CanonicalPractice(row.practiceId(), bd(row.currentSalaryDkk()), bd(row.currentOpexDkk()),
                    bd(row.currentTotalDkk()), bd(row.priorSalaryDkk()), bd(row.priorOpexDkk()),
                    bd(row.priorTotalDkk()), bd(row.totalDeltaDkk()), bd(row.totalDeltaPct()),
                    bd(row.currentAverageFte()), bd(row.priorAverageFte()), bd(row.currentCostPerFteDkk()),
                    bd(row.priorCostPerFteDkk()), bd(row.costPerFteDeltaDkk()), bd(row.costPerFteDeltaPct()),
                    12, 0, 12, 12, 0, 12);
        }
        PracticeOperatingCostDTO toLegacy() {
            return new PracticeOperatingCostDTO(practiceId, dbl(currentSalaryDkk), dbl(currentOpexDkk),
                    dbl(currentTotalDkk), dbl(priorSalaryDkk), dbl(priorOpexDkk), dbl(priorTotalDkk),
                    dbl(totalDeltaDkk), dbl(totalDeltaPct), dbl(currentAverageFte), dbl(priorAverageFte),
                    dbl(currentCostPerFteDkk), dbl(priorCostPerFteDkk), dbl(costPerFteDeltaDkk),
                    dbl(costPerFteDeltaPct));
        }
        CanonicalPractice withFteCoverage(int currentExpected, int currentCovered, int currentMissing,
                                          int priorExpected, int priorCovered, int priorMissing) {
            return new CanonicalPractice(practiceId, currentSalaryDkk, currentOpexDkk, currentTotalDkk,
                    priorSalaryDkk, priorOpexDkk, priorTotalDkk, totalDeltaDkk, totalDeltaPct,
                    currentAverageFte, priorAverageFte, currentCostPerFteDkk, priorCostPerFteDkk,
                    costPerFteDeltaDkk, costPerFteDeltaPct,
                    currentExpected, currentCovered, currentMissing,
                    priorExpected, priorCovered, priorMissing);
        }
        private static BigDecimal bd(Double value) {
            return value == null || !Double.isFinite(value) ? null : BigDecimal.valueOf(value);
        }
        private static Double dbl(BigDecimal value) { return value == null ? null : value.doubleValue(); }
    }

    public record CanonicalWindow(boolean available, String reason, LocalDate anchor,
                                  LocalDate currentStart, LocalDate currentEnd,
                                  LocalDate priorStart, LocalDate priorEnd) {
        boolean validShape() {
            boolean allDates = anchor != null && currentStart != null && currentEnd != null
                    && priorStart != null && priorEnd != null;
            boolean noDates = anchor == null && currentStart == null && currentEnd == null
                    && priorStart == null && priorEnd == null;
            return available ? reason == null && allDates
                    : "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW".equals(reason) && noDates;
        }
    }

    private record IntegrityPointer(boolean servingEnabled, String basisGenerationId,
                                    Instant generationAt, Instant publishedAt, LocalDate coverageStart,
                                    String costFingerprint, String candidateFingerprint,
                                    String certifiedRequestId, String certifiedRequestVector,
                                    CanonicalWindow bookedWindow,
                                    CanonicalWindow bookedPlusDraftWindow,
                                    BigInteger publicationVersion) {
        CanonicalWindow window(CostSource source) {
            return source == CostSource.BOOKED ? bookedWindow : bookedPlusDraftWindow;
        }
    }
}
