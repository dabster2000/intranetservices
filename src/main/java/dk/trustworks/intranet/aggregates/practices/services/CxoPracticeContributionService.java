package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionEvidenceDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPeriodDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPracticeDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticePortfolioReconciliationDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeRevenueSegmentDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_DISABLED;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_UNAVAILABLE;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.QUERY_TIMEOUT;

/** Coherent aggregate reader for published practice revenue and the canonical cost snapshot. */
@JBossLog
@ApplicationScoped
public class CxoPracticeContributionService {
    static final int QUERY_TIMEOUT_MS = 10_000;
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final List<String> CORE = List.of("PM", "BA", "CYB", "DEV", "SA");
    static final List<String> SEGMENTS = List.of("JK", "UD", "EXTERNAL", "OTHER", "UNASSIGNED");
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("PM", "Project Management"),
            Map.entry("BA", "Business Analysis"),
            Map.entry("CYB", "Cybersecurity"),
            Map.entry("DEV", "Technology"),
            Map.entry("SA", "Solution Architecture"),
            Map.entry("JK", "JK"),
            Map.entry("UD", "UD"),
            Map.entry("EXTERNAL", "External"),
            Map.entry("OTHER", "Other"),
            Map.entry("UNASSIGNED", "Unassigned"));
    private static final List<String> SOURCE_REASON_PRECEDENCE = List.of(
            "SOURCE_CLASSIFICATION_UNAVAILABLE", "SOURCE_DUPLICATE_EVIDENCE_AMBIGUOUS",
            "PHANTOM_DUPLICATE_RISK", "SOURCE_DUPLICATE_RISK",
            "HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE",
            "HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE", "SOURCE_COUNT_INCOMPLETE");
    private static final List<String> VALUATION_REASON_PRECEDENCE = List.of(
            "GL_CONTROL_AMBIGUOUS", "GL_CONTROL_SIGN_MISMATCH",
            "PHANTOM_ITEM_GRAIN_UNSUPPORTED", "HEADER_DISCOUNT_OUT_OF_RANGE",
            "HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE", "OFFSETTING_ITEM_CONTROL_UNAVAILABLE",
            "HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE", "GL_CONTROL_MISSING",
            "FX_RATE_AMBIGUOUS", "FX_RATE_INVALID", "FX_RATE_MISSING",
            "GL_CONTROL_RESIDUAL_UNASSIGNED", "PROVISIONAL_MONTHLY_FX", "PROVISIONAL_NATIVE_DKK");
    private static final List<String> ATTRIBUTION_REASON_PRECEDENCE = List.of(
            "CREDIT_SOURCE_AMBIGUOUS", "CREDIT_SOURCE_MISSING", "DELIVERY_EVIDENCE_AMBIGUOUS",
            "CONSULTANT_TYPE_UNAVAILABLE", "ATTRIBUTION_INVALID",
            "ATTRIBUTION_RESIDUAL_UNASSIGNED", "ATTRIBUTION_ESTIMATED");

    static final String SNAPSHOT_SQL = """
            SELECT c.contribution_serving_enabled, c.control_version,
                   p.status, p.published_generation_id, p.paired_cost_generation_at,
                   p.practice_basis_generation_id, p.full_bi_refresh_version,
                   p.invoice_document_source_version, p.finance_gl_source_version,
                   p.currency_source_version, p.account_classification_source_version,
                   p.invoice_attribution_source_version, p.self_billed_source_version,
                   p.phantom_attribution_source_version, p.delivery_evidence_source_version,
                   p.practice_basis_input_source_version,
                   p.booked_available, p.booked_reason, p.booked_anchor_month,
                   p.booked_current_start_month, p.booked_current_end_month,
                   p.booked_prior_start_month, p.booked_prior_end_month,
                   p.booked_plus_draft_available, p.booked_plus_draft_reason,
                   p.booked_plus_draft_anchor_month, p.booked_plus_draft_current_start_month,
                   p.booked_plus_draft_current_end_month, p.booked_plus_draft_prior_start_month,
                   p.booked_plus_draft_prior_end_month,
                   p.published_at, p.refreshed_at, p.publication_version,
                   o.refresh_state, o.active_refresh_token, o.generation_at, o.published_at,
                   o.practice_basis_generation_id, o.latest_cost_basis_request_id,
                   o.latest_cost_basis_request_vector, o.certified_cost_basis_request_id,
                   o.certified_cost_basis_request_vector,
                   p.coverage_start_month, p.coverage_end_month, o.publication_version
            FROM practice_contribution_publication_control c
            CROSS JOIN practice_revenue_publication p
            CROSS JOIN practice_operating_cost_publication o
            WHERE c.control_id = 1
              AND p.publication_key = 'PRACTICE_CONTRIBUTION'
              AND o.publication_id = 1
            """;

    static final String WATERMARK_SQL = """
            SELECT source_name, source_version, source_state
            FROM practice_revenue_source_watermark
            ORDER BY source_name
            """;

    static final String LATEST_REQUEST_SQL = """
            SELECT request_id, status, request_key, input_vector_fingerprint,
                   resulting_cost_generation_at, compared_cost_generation_at,
                   resulting_basis_generation_id, compared_basis_generation_id,
                   superseded_by_request_id
            FROM practice_cost_basis_refresh_request
            WHERE request_id = :requestId
            """;

    @Inject EntityManager em;
    @Inject PracticeCostSnapshotProvider costSnapshotProvider;
    @Inject PracticeContributionReadTransactionRunner readTransactions;
    @ConfigProperty(name = "practices.contribution.request-timeout", defaultValue = "PT10S")
    Duration requestTimeout;
    LongSupplier monotonicNanos = System::nanoTime;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public PracticeContributionResponseDTO getContribution(CostSource requestedSource) {
        CostSource source = requestedSource == null ? CostSource.BOOKED : requestedSource;
        Duration boundedRequestTimeout = boundedRequestTimeout();
        long deadline = monotonicNanos.getAsLong() + boundedRequestTimeout.toNanos();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                ReadAttempt body = inReadTransaction(deadline, () -> readBody(source, deadline));
                CoherenceToken after;
                try {
                    after = inReadTransaction(deadline, () -> loadCoherenceToken(source, deadline));
                } catch (ContributionUnavailableException changed) {
                    if (QUERY_TIMEOUT.equals(changed.getMessage()) || attempt == 1) throw changed;
                    log.warnf("practice contribution publication became unavailable during read: attempt=%d",
                            attempt + 1);
                    continue;
                }
                if (body.token().equals(after)) return body.response();
                log.warnf("practice contribution publication changed during read: attempt=%d", attempt + 1);
            }
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        } catch (ContributionUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                log.warn("practice contribution request timed out");
                throw new ContributionUnavailableException(QUERY_TIMEOUT);
            }
            log.error("practice contribution aggregate read failed", e);
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
    }

    private ReadAttempt readBody(CostSource source, long deadline) {
        CoherenceToken token = loadCoherenceToken(source, deadline);
        PracticeCostSnapshotProvider.Snapshot cost = costSnapshotProvider.getSnapshot(
                source, () -> remainingMillis(deadline));
        PracticeContributionResponseDTO response = buildResponse(
                token.publication(), cost.canonical(), source, () -> remainingMillis(deadline));
        return new ReadAttempt(token, response);
    }

    private CoherenceToken loadCoherenceToken(CostSource source, long deadline) {
        PublicationSnapshot publication = loadPublicationSnapshot(source, remainingMillis(deadline));
        validatePublication(publication);
        Map<String, Watermark> watermarks = loadWatermarks(remainingMillis(deadline));
        validateWatermarks(publication, watermarks);
        CostRequestCertification request = validateCertifiedCostRequest(
                publication, remainingMillis(deadline));
        return new CoherenceToken(publication, watermarks, request);
    }

    private <T> T inReadTransaction(long deadline, Supplier<T> work) {
        return readTransactions.requiringNew(remainingWholeSeconds(deadline), work);
    }

    private Duration boundedRequestTimeout() {
        Duration configured = requestTimeout == null ? MAX_REQUEST_TIMEOUT : requestTimeout;
        if (configured.isZero() || configured.isNegative() || configured.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            log.error("invalid practices.contribution.request-timeout; refusing contribution read");
            throw new ContributionUnavailableException(QUERY_TIMEOUT);
        }
        return configured;
    }

    private int remainingWholeSeconds(long deadline) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(deadline - monotonicNanos.getAsLong());
        if (seconds < 1) throw new ContributionUnavailableException(QUERY_TIMEOUT);
        return Math.toIntExact(Math.min(TimeUnit.MILLISECONDS.toSeconds(QUERY_TIMEOUT_MS), seconds));
    }

    private int remainingMillis(long deadline) {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - monotonicNanos.getAsLong());
        if (millis < 1) throw new ContributionUnavailableException(QUERY_TIMEOUT);
        return Math.toIntExact(Math.min(QUERY_TIMEOUT_MS, millis));
    }

    private PracticeContributionResponseDTO buildResponse(
            PublicationSnapshot publication,
            PracticeCostSnapshotProvider.CanonicalSnapshot cost,
            CostSource source) {
        return buildResponse(publication, cost, source, () -> QUERY_TIMEOUT_MS);
    }

    private PracticeContributionResponseDTO buildResponse(
            PublicationSnapshot publication,
            PracticeCostSnapshotProvider.CanonicalSnapshot cost,
            CostSource source,
            QueryTimeoutBudget queryTimeout) {
        SelectedWindow window = publication.window();
        boolean costAvailable = cost != null && cost.windowAvailable();
        if (cost == null || !source.name().equals(cost.costSource())
                || window.available() != costAvailable
                || (!costAvailable && !Objects.equals(window.reason(), cost.windowReason()))
                || (costAvailable && !windowMatchesCost(window, cost))) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }

        PeriodAggregate current = costAvailable
                ? loadPeriod(publication.generationId(), window.currentStart(), window.currentEnd(), queryTimeout)
                : PeriodAggregate.unavailable();
        PeriodAggregate prior = costAvailable
                ? loadPeriod(publication.generationId(), window.priorStart(), window.priorEnd(), queryTimeout)
                : PeriodAggregate.unavailable();

        Map<String, PracticeCostSnapshotProvider.CanonicalPractice> costs = new HashMap<>();
        if (cost != null) cost.practices().forEach(row -> costs.put(row.practiceId(), row));

        List<PracticeContributionPracticeDTO> practices = new ArrayList<>(CORE.size());
        for (String id : CORE) {
            if (!costAvailable) {
                practices.add(new PracticeContributionPracticeDTO(
                        id, LABELS.get(id), null, null,
                        null, null, null, null, null, null, null));
                continue;
            }
            PracticeCostSnapshotProvider.CanonicalPractice costRow = costs.get(id);
            CoreMetrics currentMetrics = coreMetrics(id, current, costRow, true,
                    costAvailable && cost.currentCostComplete(),
                    costAvailable && cost.currentFteComplete(), publication.basesAligned());
            CoreMetrics priorMetrics = coreMetrics(id, prior, costRow, false,
                    costAvailable && cost.priorCostComplete(),
                    costAvailable && cost.priorFteComplete(), publication.basesAligned());
            practices.add(new PracticeContributionPracticeDTO(
                    id, LABELS.get(id), currentMetrics.dto(), priorMetrics.dto(),
                    moneyDelta(currentMetrics.revenue(), priorMetrics.revenue(),
                            currentMetrics.deltaEligible(), priorMetrics.deltaEligible()),
                    pctDelta(currentMetrics.revenue(), priorMetrics.revenue(),
                            currentMetrics.deltaEligible(), priorMetrics.deltaEligible()),
                    moneyDelta(currentMetrics.cost(), priorMetrics.cost(),
                            currentMetrics.costEligible(), priorMetrics.costEligible()),
                    pctDelta(currentMetrics.cost(), priorMetrics.cost(),
                            currentMetrics.costEligible(), priorMetrics.costEligible()),
                    moneyDelta(currentMetrics.contribution(), priorMetrics.contribution(),
                            currentMetrics.deltaEligible(), priorMetrics.deltaEligible()),
                    pctDelta(currentMetrics.contribution(), priorMetrics.contribution(),
                            currentMetrics.deltaEligible(), priorMetrics.deltaEligible()),
                    pointDelta(currentMetrics.margin(), priorMetrics.margin(),
                            currentMetrics.deltaEligible(), priorMetrics.deltaEligible())));
        }

        List<PracticeRevenueSegmentDTO> segments = SEGMENTS.stream()
                .map(id -> costAvailable ? toSegment(id, current, prior)
                        : new PracticeRevenueSegmentDTO(
                                id, LABELS.get(id), null, null, null, null))
                .toList();

        String responseStatus = !costAvailable ? "UNAVAILABLE_COST"
                : current.hasBusinessGaps() || prior.hasBusinessGaps()
                || !cost.complete() || !publication.basesAligned()
                ? "AVAILABLE_WITH_GAPS" : "AVAILABLE";
        String responseReason = !costAvailable ? "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW" : null;

        return new PracticeContributionResponseDTO(
                "CONSOLIDATED_GROUP",
                "INVOICED",
                source.name(), responseStatus, responseReason,
                month(window.anchor()),
                costAvailable ? toPeriod(window.currentStart(), window.currentEnd(), current, cost, true,
                        publication.basesAligned()) : null,
                costAvailable ? toPeriod(window.priorStart(), window.priorEnd(), prior, cost, false,
                        publication.basesAligned()) : null,
                publication.generationId(), publication.publishedAt(), publication.refreshedAt(),
                publication.fullBiVersion().toString(), stringifyVersions(publication.sourceVersions()),
                publication.pairedCostGenerationAt(), publication.costPublishedAt(),
                publication.basisGenerationId(),
                "INVOICE_CONTROL_WITH_DELIVERY_ATTRIBUTION_V1",
                "SIGNED_GL_EFFECTIVE_PRACTICE_V1",
                publication.coverageStart(), cost == null ? null : cost.practiceAttributionCoverageStartDate(),
                publication.basesAligned(), publication.basesAligned() ? null : "PRACTICE_BASIS_UNALIGNED",
                costAvailable ? portfolio(current) : null,
                costAvailable ? portfolio(prior) : null,
                practices, segments);
    }

    /** Compatibility seam for existing pure contract fixtures; production uses the canonical record. */
    private PracticeContributionResponseDTO buildResponse(
            PublicationSnapshot publication, PracticeOperatingCostResponseDTO cost, CostSource source) {
        return buildResponse(publication,
                cost == null ? null : PracticeCostSnapshotProvider.CanonicalSnapshot.fromLegacy(cost), source);
    }

    private CoreMetrics coreMetrics(String id, PeriodAggregate period,
                                    PracticeCostSnapshotProvider.CanonicalPractice costs,
                                    boolean current, boolean costComplete, boolean fteComplete,
                                    boolean basesAligned) {
        SegmentAggregate revenue = period.bySegment().getOrDefault(id, SegmentAggregate.ZERO);
        BigDecimal salary = costs == null ? null : current ? costs.currentSalaryDkk() : costs.priorSalaryDkk();
        BigDecimal opex = costs == null ? null : current ? costs.currentOpexDkk() : costs.priorOpexDkk();
        if (!costComplete) {
            salary = null;
            opex = null;
        }
        BigDecimal cost = salary == null || opex == null ? null : salary.add(opex);
        BigDecimal averageFte = costs == null ? null : current ? costs.currentAverageFte() : costs.priorAverageFte();
        BigDecimal costPerFte = costs == null ? null : current ? costs.currentCostPerFteDkk() : costs.priorCostPerFteDkk();
        if (!fteComplete) {
            averageFte = null;
            costPerFte = null;
        }

        boolean revenueAvailable = period.revenueAvailable(id, revenue);
        String valuationStatus = period.valuationStatus(id, revenue);
        String valuationReason = period.valuationReason(id, revenue);
        String attributionStatus = period.coreAttributionStatus(revenue);
        String attributionReason = period.coreAttributionReason(revenue);
        BigDecimal authoritativeRevenue = revenueAvailable ? revenue.authoritative() : null;
        BigDecimal contribution = authoritativeRevenue != null && costComplete && cost != null && basesAligned
                ? authoritativeRevenue.subtract(cost) : null;
        BigDecimal margin = contribution != null && authoritativeRevenue.signum() > 0
                ? contribution.divide(authoritativeRevenue, MathContext.DECIMAL128)
                    .multiply(BigDecimal.valueOf(100)) : null;
        String status = !revenueAvailable ? "UNAVAILABLE_REVENUE"
                : !costComplete ? "UNAVAILABLE_COST"
                : !basesAligned ? "UNALIGNED_PRACTICE_BASIS"
                : "PARTIAL".equals(attributionStatus) ? "PARTIAL_ATTRIBUTION"
                : "ESTIMATED".equals(attributionStatus) ? "ESTIMATED_ATTRIBUTION" : "CONFIRMED";
        boolean deltaEligible = ("CONFIRMED".equals(status) || "ESTIMATED_ATTRIBUTION".equals(status))
                && "COMPLETE".equals(period.sourceStatus(id, revenue))
                && "CONFIRMED_GL".equals(valuationStatus);

        PracticeContributionPracticeDTO.Metrics dto = new PracticeContributionPracticeDTO.Metrics(
                money(authoritativeRevenue), money(period.provisionalRevenue(id, revenue)),
                money(salary), money(opex), money(cost),
                money(contribution), decimalString(margin), decimalString(averageFte), money(costPerFte),
                money(revenue.confirmed()), money(revenue.estimated()), money(revenue.partialAffected()),
                money(BigDecimal.ZERO),
                period.sourceStatus(id, revenue), period.sourceReason(id, revenue),
                pct(revenue.attributedAbs(), revenue.totalAbs()),
                valuationStatus, valuationReason, attributionStatus,
                attributionReason, costComplete ? "COMPLETE" : "INCOMPLETE",
                costComplete ? null : "COST_EVIDENCE_INCOMPLETE",
                fteComplete ? "COMPLETE" : "INCOMPLETE",
                fteComplete ? null : "FTE_EVIDENCE_INCOMPLETE",
                costs == null ? 0 : current ? costs.currentExpectedFteCellCount() : costs.priorExpectedFteCellCount(),
                costs == null ? 0 : current ? costs.currentCoveredFteCellCount() : costs.priorCoveredFteCellCount(),
                costs == null ? 0 : current ? costs.currentMissingFteCellCount() : costs.priorMissingFteCellCount(),
                basesAligned ? "ALIGNED_EFFECTIVE_DATED" : "UNALIGNED",
                basesAligned ? null : "PRACTICE_BASIS_UNALIGNED",
                status, availabilityReason(status, period.sourceReason(id, revenue),
                        valuationReason, attributionReason));
        return new CoreMetrics(dto, authoritativeRevenue, cost, contribution, margin,
                deltaEligible, costComplete && cost != null);
    }

    /** Compatibility seam for existing pure contract fixtures. */
    private CoreMetrics coreMetrics(String id, PeriodAggregate period, PracticeOperatingCostDTO costs,
                                    boolean current, boolean costComplete, boolean fteComplete,
                                    boolean basesAligned) {
        PracticeCostSnapshotProvider.CanonicalPractice canonical = costs == null
                ? null : PracticeCostSnapshotProvider.CanonicalPractice.fromLegacy(costs);
        if (canonical != null) {
            canonical = canonical.withFteCoverage(
                    12, fteComplete ? 12 : 0, fteComplete ? 0 : 12,
                    12, fteComplete ? 12 : 0, fteComplete ? 0 : 12);
        }
        return coreMetrics(id, period, canonical, current, costComplete, fteComplete, basesAligned);
    }

    private PracticeRevenueSegmentDTO toSegment(String id, PeriodAggregate current, PeriodAggregate prior) {
        SegmentMetrics c = segmentMetrics(id, current);
        SegmentMetrics p = segmentMetrics(id, prior);
        return new PracticeRevenueSegmentDTO(id, LABELS.get(id), c.dto(), p.dto(),
                moneyDelta(c.display(), p.display(), c.deltaEligible(), p.deltaEligible()),
                pctDelta(c.display(), p.display(), c.deltaEligible(), p.deltaEligible()));
    }

    private SegmentMetrics segmentMetrics(String id, PeriodAggregate period) {
        SegmentAggregate value = period.bySegment().getOrDefault(id, SegmentAggregate.ZERO);
        String valuationStatus = period.valuationStatus(id, value);
        String valuationReason = period.valuationReason(id, value);
        String attributionStatus = period.attributionStatus(value);
        String attributionReason = period.attributionReason(value);
        String displayStatus = !period.revenueAvailable(id, value) ? "UNAVAILABLE"
                : "PARTIAL".equals(attributionStatus) ? "PARTIAL"
                : "ESTIMATED".equals(attributionStatus) ? "ESTIMATED" : "CONFIRMED";
        BigDecimal display = "UNAVAILABLE".equals(displayStatus) ? null : value.authoritative();
        boolean deltaEligible = !"UNASSIGNED".equals(id)
                && ("CONFIRMED".equals(displayStatus) || "ESTIMATED".equals(displayStatus))
                && "COMPLETE".equals(period.sourceStatus(id, value))
                && "CONFIRMED_GL".equals(valuationStatus);
        return new SegmentMetrics(new PracticeRevenueSegmentDTO.Metrics(
                money(display), displayStatus,
                money(period.revenueAvailable(id, value) ? value.authoritative() : null),
                money(period.provisionalRevenue(id, value)), money(value.confirmed()), money(value.estimated()),
                money(value.partialAffected()), money("UNASSIGNED".equals(id) ? value.unassigned() : BigDecimal.ZERO),
                period.sourceStatus(id, value), period.sourceReason(id, value), valuationStatus, valuationReason,
                attributionStatus, attributionReason, pct(value.attributedAbs(), value.totalAbs()),
                "UNAVAILABLE".equals(displayStatus)
                        ? availabilityReason("UNAVAILABLE_REVENUE", period.sourceReason(id, value),
                        valuationReason, attributionReason)
                        : "PARTIAL".equals(displayStatus) ? attributionReason
                        : "ESTIMATED".equals(displayStatus) ? attributionReason : null,
                segmentExplanation(displayStatus)), display, deltaEligible);
    }

    private static String segmentExplanation(String status) {
        return switch (status) {
            case "PARTIAL" -> "Revenue includes explicitly unresolved recipient evidence.";
            case "ESTIMATED" -> "Revenue includes estimated attribution evidence.";
            case "UNAVAILABLE" -> "Authoritative revenue evidence is unavailable; provisional evidence remains in Details when present.";
            default -> "Revenue is supported by confirmed monetary and attribution evidence.";
        };
    }

    private PracticeContributionPeriodDTO toPeriod(LocalDate start, LocalDate end, PeriodAggregate period,
                                                    PracticeCostSnapshotProvider.CanonicalSnapshot cost, boolean current,
                                                    boolean basesAligned) {
        boolean costComplete = cost != null && (current ? cost.currentCostComplete() : cost.priorCostComplete());
        boolean fteComplete = cost != null && (current ? cost.currentFteComplete() : cost.priorFteComplete());
        boolean anyCoreRevenueUnavailable = CORE.stream()
                .anyMatch(id -> !period.revenueAvailable(id, period.segment(id)));
        boolean anyCorePartial = CORE.stream()
                .map(period::segment)
                .map(period::coreAttributionStatus)
                .anyMatch("PARTIAL"::equals);
        boolean anyCoreEstimated = CORE.stream()
                .map(period::segment)
                .map(period::coreAttributionStatus)
                .anyMatch("ESTIMATED"::equals);
        int costMonthEndFallbackCount = current
                ? cost.currentCostMonthEndFallbackEmployeeMonthCount()
                : cost.priorCostMonthEndFallbackEmployeeMonthCount();
        String explanationCode = period.attributionExplanationCode(costMonthEndFallbackCount);
        String contributionStatus = anyCoreRevenueUnavailable ? "UNAVAILABLE_REVENUE"
                : !costComplete ? "UNAVAILABLE_COST"
                : !basesAligned ? "UNALIGNED_PRACTICE_BASIS"
                : anyCorePartial ? "PARTIAL_ATTRIBUTION"
                : anyCoreEstimated ? "ESTIMATED_ATTRIBUTION" : "CONFIRMED";
        PracticeContributionEvidenceDTO evidence = new PracticeContributionEvidenceDTO(
                period.sourceDocumentCount(), period.sourceItemCount(), period.valuedItemCount(),
                pct(BigDecimal.valueOf(period.valuedItemCount()), BigDecimal.valueOf(period.sourceItemCount())),
                period.missingCount(), nativeAmounts(period.missingNativeAmounts()),
                money(period.confirmed()), money(period.estimated()),
                money(period.partialAffected()), money(period.unassigned()),
                pct(period.confirmedAbs(), period.totalAbs()), pct(period.attributedAbs(), period.totalAbs()),
                pct(period.unassignedAbs(), period.totalAbs()), period.duplicateRiskCount(),
                money(period.hasGlControlledSubset() ? period.glControl() : null),
                money(period.hasGlControlledSubset() ? period.glAllocated() : null),
                money(period.hasGlControlledSubset() ? period.reconciliationDifference() : null),
                period.reconciliationStatus());
        return new PracticeContributionPeriodDTO(
                month(start), month(end), period.sourceStatus(), period.sourceReason(),
                period.fxStatus(), period.fxReason(), period.attributionStatus(), period.attributionReason(),
                "INVOICE_CONTROL_WITH_DELIVERY_ATTRIBUTION_V1",
                period.registeredValueCount(), period.registeredHoursCount(),
                period.scheduledCapacityCount(), period.monthEndCount(),
                costMonthEndFallbackCount,
                period.historicalFallbackCount(), period.historicalFallbackCount() > 0,
                explanationCode, period.attributionExplanation(explanationCode),
                costComplete ? "COMPLETE" : "INCOMPLETE", costComplete ? null : "COST_EVIDENCE_INCOMPLETE",
                fteComplete ? "COMPLETE" : "INCOMPLETE", fteComplete ? null : "FTE_EVIDENCE_INCOMPLETE",
                current ? cost.currentExpectedFteCellCount() : cost.priorExpectedFteCellCount(),
                current ? cost.currentCoveredFteCellCount() : cost.priorCoveredFteCellCount(),
                current ? cost.currentMissingFteCellCount() : cost.priorMissingFteCellCount(),
                basesAligned ? "ALIGNED_EFFECTIVE_DATED" : "UNALIGNED",
                basesAligned ? null : "PRACTICE_BASIS_UNALIGNED",
                contributionStatus, availabilityReason(contributionStatus, period.sourceReason(),
                        period.valuationReason(), period.attributionReason()),
                evidence);
    }

    /** Compatibility seam for existing pure contract fixtures. */
    private PracticeContributionPeriodDTO toPeriod(
            LocalDate start, LocalDate end, PeriodAggregate period,
            PracticeOperatingCostResponseDTO cost, boolean current, boolean basesAligned) {
        return toPeriod(start, end, period,
                PracticeCostSnapshotProvider.CanonicalSnapshot.fromLegacy(cost), current, basesAligned);
    }

    private PracticePortfolioReconciliationDTO portfolio(PeriodAggregate period) {
        BigDecimal core = CORE.stream().map(id -> period.segment(id).authoritative())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal other = SEGMENTS.stream().map(id -> period.segment(id).authoritative())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PracticePortfolioReconciliationDTO(
                money(period.revenueAvailable() ? period.allocated() : null), money(core), money(other),
                money(period.confirmed()), money(period.estimated()), money(period.partialAffected()),
                money(period.unassigned()), pct(period.confirmedAbs(), period.totalAbs()),
                pct(period.attributedAbs(), period.totalAbs()), pct(period.unassignedAbs(), period.totalAbs()),
                period.sourceDocumentCount(), period.sourceItemCount(), period.valuedItemCount(),
                pct(BigDecimal.valueOf(period.valuedItemCount()), BigDecimal.valueOf(period.sourceItemCount())),
                period.duplicateRiskCount(), period.missingCount(),
                nativeAmounts(period.missingNativeAmounts()),
                money(period.hasGlControlledSubset() ? period.reconciliationDifference() : null),
                period.reconciliationStatus(),
                "PARTIAL_CONTROL".equals(period.reconciliationStatus())
                        ? "RECONCILIATION_PARTIAL_CONTROL"
                        : period.revenueAvailable() ? null
                        : availabilityReason("UNAVAILABLE_REVENUE", period.sourceReason(),
                        period.valuationReason(), period.attributionReason()));
    }

    private static String availabilityReason(String contributionStatus, String sourceReason,
                                             String valuationReason, String attributionReason) {
        return switch (contributionStatus) {
            case "UNAVAILABLE_REVENUE" -> sourceReason != null ? sourceReason
                    : valuationReason != null ? valuationReason
                    : attributionReason != null ? attributionReason : "CURRENT_EVIDENCE_UNAVAILABLE";
            case "UNAVAILABLE_COST" -> "COST_EVIDENCE_INCOMPLETE";
            case "UNALIGNED_PRACTICE_BASIS" -> "PRACTICE_BASIS_UNALIGNED";
            case "PARTIAL_ATTRIBUTION" -> attributionReason != null
                    ? attributionReason : "ATTRIBUTION_RESIDUAL_UNASSIGNED";
            case "ESTIMATED_ATTRIBUTION" -> attributionReason != null
                    ? attributionReason : "ATTRIBUTION_ESTIMATED";
            default -> null;
        };
    }

    private static String firstReason(Set<String> reasons, List<String> precedence) {
        for (String candidate : precedence) if (reasons.contains(candidate)) return candidate;
        return null;
    }

    private static String publicReason(String internal) {
        if (internal == null || internal.isBlank() || "NONE".equals(internal)) return null;
        return switch (internal) {
            case "ITEM_CLASSIFICATION_UNAVAILABLE" -> "SOURCE_CLASSIFICATION_UNAVAILABLE";
            case "HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE" -> "HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE";
            case "MANUAL_PHANTOM_DUPLICATE_RISK", "PHANTOM_DUPLICATE_RISK" -> "PHANTOM_DUPLICATE_RISK";
            case "CONTROL_SIGN_MISMATCH" -> "GL_CONTROL_SIGN_MISMATCH";
            case "GL_CONTROL_INVALID", "FX_PRODUCT_OVERFLOW" -> "FX_RATE_INVALID";
            case "PHANTOM_ITEM_GRAIN_INVALID" -> "PHANTOM_ITEM_GRAIN_UNSUPPORTED";
            case "NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR", "CONTROLLED_DOCUMENT_RESIDUAL" ->
                    "GL_CONTROL_RESIDUAL_UNASSIGNED";
            case "MISSING_CONSULTANT_TYPE" -> "CONSULTANT_TYPE_UNAVAILABLE";
            case "CONTRADICTORY_EVIDENCE", "DUPLICATE_SOURCE_RECORD", "ROUNDING_CONSERVATION_FAILURE" ->
                    "ATTRIBUTION_INVALID";
            case "ATTRIBUTION_MISSING", "MISSING_RECIPIENT", "PARTIAL_SOURCE_RESIDUAL" ->
                    "ATTRIBUTION_RESIDUAL_UNASSIGNED";
            case "ZERO_ITEM_DOCUMENT" -> "SOURCE_COUNT_INCOMPLETE";
            case "LEGACY_DIRECT_FALLBACK" -> "ATTRIBUTION_ESTIMATED";
            case "PHANTOM_ITEM_GRAIN_UNSUPPORTED", "HEADER_DISCOUNT_OUT_OF_RANGE",
                    "HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE", "OFFSETTING_ITEM_CONTROL_UNAVAILABLE",
                    "GL_CONTROL_MISSING", "GL_CONTROL_AMBIGUOUS", "FX_RATE_MISSING",
                    "FX_RATE_AMBIGUOUS", "FX_RATE_INVALID", "DELIVERY_EVIDENCE_AMBIGUOUS",
                    "ATTRIBUTION_INVALID", "CREDIT_SOURCE_MISSING", "CREDIT_SOURCE_AMBIGUOUS",
                    "SOURCE_DUPLICATE_RISK", "SOURCE_DUPLICATE_EVIDENCE_AMBIGUOUS",
                    "SOURCE_COUNT_INCOMPLETE", "SOURCE_CLASSIFICATION_UNAVAILABLE" -> internal;
            default -> null;
        };
    }

    private PeriodAggregate loadPeriod(
            String generationId, LocalDate start, LocalDate end, QueryTimeoutBudget queryTimeout) {
        Query allocationQuery = em.createNativeQuery("""
                SELECT a.segment_id, a.attribution_status,
                       COALESCE(SUM(CASE WHEN i.valuation_status = 'CONFIRMED_GL' THEN a.allocation_dkk ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN i.control_source IN ('NATIVE_DKK','MONTHLY_FX') THEN a.allocation_dkk ELSE 0 END), 0),
                       COALESCE(SUM(ABS(CASE WHEN i.valuation_status IN
                           ('CONFIRMED_GL','PROVISIONAL_NATIVE_DKK','PROVISIONAL_MONTHLY_FX')
                           THEN a.allocation_dkk ELSE 0 END)), 0),
                       COALESCE(SUM(ABS(CASE WHEN i.control_source IN ('NATIVE_DKK','MONTHLY_FX')
                           THEN a.allocation_dkk ELSE 0 END)), 0),
                       COALESCE(SUM(ABS(CASE WHEN i.valuation_status = 'CONFIRMED_GL'
                           THEN a.allocation_dkk ELSE 0 END)), 0),
                       SUM(CASE WHEN i.control_source IN ('NATIVE_DKK','MONTHLY_FX') THEN 1 ELSE 0 END),
                       COUNT(*),
                       SUM(CASE WHEN a.attribution_source = 'REGISTERED_VALUE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN a.attribution_source = 'REGISTERED_HOURS' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN a.practice_resolution_method = 'SCHEDULED_CAPACITY' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN a.practice_resolution_method = 'MONTH_END_PRACTICE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN a.historical_practice_fallback = TRUE THEN 1 ELSE 0 END)
                FROM fact_practice_net_revenue_allocation_mat a
                JOIN fact_practice_net_revenue_item_mat i
                  ON i.generation_id = a.generation_id AND i.item_control_key = a.item_control_key
                WHERE a.generation_id = :generationId
                  AND i.recognized_month BETWEEN :fromMonth AND :toMonth
                GROUP BY a.segment_id, a.attribution_status
                ORDER BY a.segment_id, a.attribution_status
                """);
        bindPeriod(allocationQuery, generationId, start, end, queryTimeout.remainingMillis());
        @SuppressWarnings("unchecked") List<Object[]> allocationRows = allocationQuery.getResultList();

        Query evidenceQuery = em.createNativeQuery("""
                SELECT COUNT(DISTINCT source_document_uuid),
                       SUM(CASE WHEN row_kind = 'SOURCE_ITEM' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN row_kind = 'SOURCE_ITEM' AND
                           (item_control_dkk IS NOT NULL OR valuation_status = 'CONTROLLED_BY_DOCUMENT_RESIDUAL')
                           THEN 1 ELSE 0 END),
                       SUM(CASE WHEN row_kind = 'SOURCE_ITEM' AND item_control_dkk IS NULL
                           AND valuation_status <> 'CONTROLLED_BY_DOCUMENT_RESIDUAL' THEN 1 ELSE 0 END)
                           AS missing_dkk_control_count,
                       COUNT(DISTINCT CASE WHEN duplicate_risk_status <> 'NONE' THEN source_document_uuid END),
                       COALESCE(SUM(CASE WHEN valuation_status = 'CONFIRMED_GL' THEN item_control_dkk ELSE 0 END),0),
                       COALESCE(SUM(CASE WHEN control_source = 'ECONOMIC_GL' AND row_kind <> 'DOCUMENT_EVIDENCE' THEN item_control_dkk ELSE 0 END),0),
                       SUM(CASE WHEN valuation_status = 'PROVISIONAL_NATIVE_DKK' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN valuation_status = 'PROVISIONAL_MONTHLY_FX' THEN 1 ELSE 0 END)
                FROM fact_practice_net_revenue_item_mat
                WHERE generation_id = :generationId
                  AND recognized_month BETWEEN :fromMonth AND :toMonth
                """);
        bindPeriod(evidenceQuery, generationId, start, end, queryTimeout.remainingMillis());
        @SuppressWarnings("unchecked") List<Object[]> evidenceRows = evidenceQuery.getResultList();
        Object[] evidence = evidenceRows.isEmpty() ? new Object[9] : evidenceRows.getFirst();

        Query scopeEvidenceQuery = em.createNativeQuery("""
                SELECT row_kind, source_document_uuid, valuation_status, duplicate_risk_status,
                       native_currency, signed_native_control, item_control_dkk,
                       evidence_resolved_segment, scope_resolution_status
                FROM fact_practice_net_revenue_item_mat
                WHERE generation_id = :generationId
                  AND recognized_month BETWEEN :fromMonth AND :toMonth
                  AND (
                       row_kind = 'DOCUMENT_EVIDENCE'
                       OR (row_kind = 'SOURCE_ITEM' AND item_control_dkk IS NULL
                           AND valuation_status <> 'CONTROLLED_BY_DOCUMENT_RESIDUAL')
                       OR duplicate_risk_status <> 'NONE'
                  )
                ORDER BY source_document_uuid, item_control_key
                """);
        bindPeriod(scopeEvidenceQuery, generationId, start, end, queryTimeout.remainingMillis());
        @SuppressWarnings("unchecked") List<Object[]> scopeEvidenceRows = scopeEvidenceQuery.getResultList();
        EvidenceScopeSummary scopeEvidence = summarizeEvidenceScope(scopeEvidenceRows);

        Query reconciliationQuery = em.createNativeQuery("""
                SELECT COALESCE(SUM(control_dkk), 0), COALESCE(SUM(allocated_dkk), 0), COUNT(*)
                FROM (
                    SELECT i.source_document_uuid,
                           MAX(i.document_control_dkk) AS control_dkk,
                           SUM(a.allocation_dkk) AS allocated_dkk
                    FROM fact_practice_net_revenue_item_mat i
                    JOIN fact_practice_net_revenue_allocation_mat a
                      ON a.generation_id = i.generation_id
                     AND a.item_control_key = i.item_control_key
                    WHERE i.generation_id = :generationId
                      AND i.recognized_month BETWEEN :fromMonth AND :toMonth
                      AND i.control_source = 'ECONOMIC_GL'
                      AND i.valuation_status = 'CONFIRMED_GL'
                    GROUP BY i.source_document_uuid
                ) controlled_documents
                """);
        bindPeriod(reconciliationQuery, generationId, start, end, queryTimeout.remainingMillis());
        Object[] reconciliation = (Object[]) reconciliationQuery.getSingleResult();

        Map<String, MutableSegment> mutable = new LinkedHashMap<>();
        int registered = 0, registeredHours = 0, scheduled = 0, monthEnd = 0, historical = 0;
        for (Object[] cells : allocationRows) {
            AllocationRow row = new AllocationRow(cells);
            MutableSegment target = mutable.computeIfAbsent(row.segment(), ignored -> new MutableSegment());
            BigDecimal authoritative = row.confirmedGlSigned();
            BigDecimal provisional = row.provisionalSigned();
            target.authoritative = target.authoritative.add(authoritative);
            target.provisional = target.provisional.add(provisional);
            target.totalAbs = target.totalAbs.add(row.usableAbs());
            target.provisionalAbs = target.provisionalAbs.add(row.provisionalAbs());
            target.provisionalCount += row.provisionalItemCount();
            if ("CONFIRMED".equals(row.attributionStatus())) {
                target.confirmed = target.confirmed.add(authoritative);
                // Confirmed coverage is CONFIRMED_GL valuation only; the broader attributed
                // coverage below keeps the provisional movement in usableAbs().
                target.confirmedAttributedAbs = target.confirmedAttributedAbs.add(row.confirmedGlAbs());
                target.attributedAbs = target.attributedAbs.add(row.usableAbs());
            } else if ("ESTIMATED".equals(row.attributionStatus())) {
                target.estimated = target.estimated.add(authoritative);
                target.estimatedAbs = target.estimatedAbs.add(row.usableAbs());
                target.attributedAbs = target.attributedAbs.add(row.usableAbs());
            } else {
                target.unassigned = target.unassigned.add(authoritative).add(provisional);
                target.unassignedAbs = target.unassignedAbs.add(row.usableAbs());
            }
            registered += row.registeredValueCount();
            registeredHours += row.registeredHoursCount();
            scheduled += row.scheduledCapacityCount();
            monthEnd += row.monthEndCount();
            historical += row.historicalFallbackCount();
        }

        Query partialQuery = em.createNativeQuery("""
                SELECT i.item_control_key, i.item_control_dkk,
                       GROUP_CONCAT(DISTINCT CASE WHEN a.allocation_dkk <> 0 THEN a.segment_id END
                                    ORDER BY a.segment_id SEPARATOR ',')
                FROM fact_practice_net_revenue_item_mat i
                JOIN fact_practice_net_revenue_allocation_mat a
                  ON a.generation_id = i.generation_id AND a.item_control_key = i.item_control_key
                WHERE i.generation_id = :generationId
                  AND i.recognized_month BETWEEN :fromMonth AND :toMonth
                  AND i.valuation_status IN
                      ('CONFIRMED_GL','PROVISIONAL_NATIVE_DKK','PROVISIONAL_MONTHLY_FX')
                GROUP BY i.item_control_key, i.item_control_dkk
                HAVING SUM(CASE WHEN a.segment_id = 'UNASSIGNED' AND a.allocation_dkk <> 0 THEN 1 ELSE 0 END) > 0
                ORDER BY i.item_control_key
                """);
        bindPeriod(partialQuery, generationId, start, end, queryTimeout.remainingMillis());
        @SuppressWarnings("unchecked") List<Object[]> partialRows = partialQuery.getResultList();
        BigDecimal partialAffected = BigDecimal.ZERO;
        boolean sharedUnresolvedAttribution = false;
        for (Object[] row : partialRows) {
            BigDecimal affectedControl = bd(row[1]);
            partialAffected = partialAffected.add(affectedControl);
            String segments = str(row[2]);
            if (segments == null) continue;
            if ("UNASSIGNED".equals(segments)) sharedUnresolvedAttribution = true;
            for (String segment : segments.split(",")) {
                MutableSegment target = mutable.computeIfAbsent(segment, ignored -> new MutableSegment());
                target.partialAffected = target.partialAffected.add(affectedControl);
                target.partialAffectedAbs = target.partialAffectedAbs.add(affectedControl.abs());
            }
        }
        Query reasonQuery = em.createNativeQuery("""
                SELECT COALESCE(a.segment_id, i.evidence_resolved_segment),
                       i.validation_reason_code, a.residual_reason, i.duplicate_risk_status
                FROM fact_practice_net_revenue_item_mat i
                LEFT JOIN fact_practice_net_revenue_allocation_mat a
                  ON a.generation_id=i.generation_id AND a.item_control_key=i.item_control_key
                WHERE i.generation_id=:generationId
                  AND i.recognized_month BETWEEN :fromMonth AND :toMonth
                GROUP BY COALESCE(a.segment_id, i.evidence_resolved_segment),
                         i.validation_reason_code, a.residual_reason, i.duplicate_risk_status
                ORDER BY COALESCE(a.segment_id, i.evidence_resolved_segment),
                         i.validation_reason_code, a.residual_reason, i.duplicate_risk_status
                """);
        bindPeriod(reasonQuery, generationId, start, end, queryTimeout.remainingMillis());
        @SuppressWarnings("unchecked") List<Object[]> reasonRows = reasonQuery.getResultList();
        Set<String> periodReasons = new LinkedHashSet<>();
        for (Object[] row : reasonRows) {
            String segment = str(row[0]);
            for (int index = 1; index < row.length; index++) {
                String reason = publicReason(str(row[index]));
                if (reason == null) continue;
                periodReasons.add(reason);
                if (segment != null) {
                    mutable.computeIfAbsent(segment, ignored -> new MutableSegment()).reasons.add(reason);
                }
            }
        }
        Map<String, SegmentAggregate> bySegment = new LinkedHashMap<>();
        mutable.forEach((key, value) -> bySegment.put(key, value.freeze()));
        BigDecimal confirmed = bySegment.values().stream().map(SegmentAggregate::confirmed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimated = bySegment.values().stream().map(SegmentAggregate::estimated)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unassigned = bySegment.values().stream().map(SegmentAggregate::unassigned)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allocated = bySegment.values().stream().map(SegmentAggregate::authoritative)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAbs = bySegment.values().stream().map(SegmentAggregate::totalAbs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int missing = integer(evidence[3]);
        int duplicateRisk = integer(evidence[4]);
        if (missing != scopeEvidence.missingControlCount()
                || duplicateRisk != scopeEvidence.duplicateRiskCount()) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        BigDecimal gl = bd(reconciliation[0]);
        BigDecimal glAllocated = bd(reconciliation[1]);
        int glDocumentCount = integer(reconciliation[2]);
        BigDecimal diff = glAllocated.subtract(gl);
        if (glDocumentCount > 0) {
            BigDecimal tolerance = gl.abs().multiply(new BigDecimal("0.0001")).max(BigDecimal.ONE);
            if (diff.abs().compareTo(tolerance) > 0) {
                throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
            }
        }
        return new PeriodAggregate(bySegment, integer(evidence[0]), integer(evidence[1]), integer(evidence[2]),
                missing, duplicateRisk, gl, glAllocated, glDocumentCount, allocated, diff,
                confirmed, estimated, unassigned, partialAffected,
                totalAbs, registered, registeredHours, scheduled, monthEnd, historical,
                integer(evidence[7]), integer(evidence[8]), periodReasons,
                scopeEvidence.unresolvedEvidenceGapCount(), scopeEvidence.scopedEvidenceGapCounts(),
                scopeEvidence.unresolvedDuplicateRiskCount(), scopeEvidence.scopedDuplicateRiskCounts(),
                scopeEvidence.missingNativeAmounts(), sharedUnresolvedAttribution);
    }

    static EvidenceScopeSummary summarizeEvidenceScope(List<Object[]> rows) {
        int missingControlCount = 0;
        int unresolvedEvidenceGapCount = 0;
        Map<String, Integer> scopedEvidenceGapCounts = new LinkedHashMap<>();
        Map<String, BigDecimal> missingNativeAmounts = new java.util.TreeMap<>();
        Map<String, Set<EvidenceScopeKey>> duplicateScopesByDocument = new LinkedHashMap<>();

        for (Object[] row : rows == null ? List.<Object[]>of() : rows) {
            String rowKind = str(row[0]);
            String documentUuid = str(row[1]);
            String valuationStatus = str(row[2]);
            String duplicateRiskStatus = str(row[3]);
            BigDecimal itemControl = nullableBd(row[6]);
            EvidenceScopeKey scope = evidenceScope(str(row[7]), str(row[8]));
            boolean missingSourceItem = "SOURCE_ITEM".equals(rowKind) && itemControl == null
                    && !"CONTROLLED_BY_DOCUMENT_RESIDUAL".equals(valuationStatus);
            boolean zeroItemEvidence = "DOCUMENT_EVIDENCE".equals(rowKind);

            if (missingSourceItem || zeroItemEvidence) {
                if (scope.resolved()) {
                    scopedEvidenceGapCounts.merge(scope.segment(), 1, Integer::sum);
                } else {
                    unresolvedEvidenceGapCount++;
                }
            }
            if (missingSourceItem) {
                missingControlCount++;
                String currency = isoCurrency(str(row[4]));
                BigDecimal nativeAmount = nullableBd(row[5]);
                if (currency != null && nativeAmount != null) {
                    missingNativeAmounts.merge(currency, nativeAmount, BigDecimal::add);
                }
            }
            if (duplicateRiskStatus != null && !duplicateRiskStatus.isBlank()
                    && !"NONE".equals(duplicateRiskStatus)) {
                duplicateScopesByDocument.computeIfAbsent(String.valueOf(documentUuid), ignored -> new java.util.HashSet<>())
                        .add(scope);
            }
        }

        int unresolvedDuplicateRiskCount = 0;
        Map<String, Integer> scopedDuplicateRiskCounts = new LinkedHashMap<>();
        for (Set<EvidenceScopeKey> scopes : duplicateScopesByDocument.values()) {
            if (scopes.size() == 1 && scopes.iterator().next().resolved()) {
                scopedDuplicateRiskCounts.merge(scopes.iterator().next().segment(), 1, Integer::sum);
            } else {
                unresolvedDuplicateRiskCount++;
            }
        }
        return new EvidenceScopeSummary(missingControlCount, unresolvedEvidenceGapCount,
                scopedEvidenceGapCounts, duplicateScopesByDocument.size(), unresolvedDuplicateRiskCount,
                scopedDuplicateRiskCounts, missingNativeAmounts);
    }

    private static EvidenceScopeKey evidenceScope(String segment, String status) {
        boolean recognizedSegment = segment != null && LABELS.containsKey(segment);
        return "RESOLVED".equals(status) && recognizedSegment
                ? new EvidenceScopeKey(true, segment) : new EvidenceScopeKey(false, null);
    }

    private static String isoCurrency(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : null;
    }

    private static Map<String, String> nativeAmounts(Map<String, BigDecimal> amounts) {
        Map<String, String> result = new LinkedHashMap<>();
        amounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            BigDecimal normalized = entry.getValue().stripTrailingZeros();
            result.put(entry.getKey(), (normalized.signum() == 0 ? BigDecimal.ZERO : normalized).toPlainString());
        });
        return result;
    }

    private static BigDecimal nullableBd(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private void bindPeriod(
            Query query, String generationId, LocalDate start, LocalDate end, int queryTimeoutMs) {
        query.setParameter("generationId", generationId);
        query.setParameter("fromMonth", start);
        query.setParameter("toMonth", end);
        query.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
    }

    private PublicationSnapshot loadPublicationSnapshot(CostSource source) {
        return loadPublicationSnapshot(source, QUERY_TIMEOUT_MS);
    }

    private PublicationSnapshot loadPublicationSnapshot(CostSource source, int queryTimeoutMs) {
        Query query = em.createNativeQuery(SNAPSHOT_SQL);
        query.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        if (rows.size() != 1) throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        Object[] r = rows.getFirst();
        Map<String, BigInteger> versions = new LinkedHashMap<>();
        versions.put("INVOICE_DOCUMENT", bi(r[7]));
        versions.put("FINANCE_GL", bi(r[8]));
        versions.put("CURRENCY", bi(r[9]));
        versions.put("ACCOUNT_CLASSIFICATION", bi(r[10]));
        versions.put("INVOICE_ATTRIBUTION", bi(r[11]));
        versions.put("SELF_BILLED", bi(r[12]));
        versions.put("PHANTOM_ATTRIBUTION", bi(r[13]));
        versions.put("DELIVERY_EVIDENCE", bi(r[14]));
        versions.put("PRACTICE_BASIS_INPUT", bi(r[15]));
        boolean booked = bool(r[16]);
        SelectedWindow bookedWindow = new SelectedWindow(booked, str(r[17]), date(r[18]), date(r[19]),
                date(r[20]), date(r[21]), date(r[22]));
        boolean bookedDraft = bool(r[23]);
        SelectedWindow draftWindow = new SelectedWindow(bookedDraft, str(r[24]), date(r[25]), date(r[26]),
                date(r[27]), date(r[28]), date(r[29]));
        return new PublicationSnapshot(bool(r[0]), bi(r[1]), str(r[2]), str(r[3]), instant(r[4]), str(r[5]),
                bi(r[6]), versions, source == CostSource.BOOKED ? bookedWindow : draftWindow,
                instant(r[30]), instant(r[31]), bi(r[32]), str(r[33]), str(r[34]), instant(r[35]),
                instant(r[36]), str(r[37]), biNullable(r[38]), str(r[39]), biNullable(r[40]), str(r[41]),
                date(r[42]), date(r[43]), bi(r[44]));
    }

    private Map<String, Watermark> loadWatermarks() {
        return loadWatermarks(QUERY_TIMEOUT_MS);
    }

    private Map<String, Watermark> loadWatermarks(int queryTimeoutMs) {
        Query query = em.createNativeQuery(WATERMARK_SQL);
        query.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        Map<String, Watermark> result = new LinkedHashMap<>();
        for (Object[] row : rows) result.put(str(row[0]), new Watermark(bi(row[1]), str(row[2])));
        return result;
    }

    private void validatePublication(PublicationSnapshot p) {
        if (!p.servingEnabled()) throw new ContributionUnavailableException(PUBLICATION_DISABLED);
        if (!"READY".equals(p.status()) || p.generationId() == null || p.publishedAt() == null
                || p.revenueCoverageStart() == null || p.revenueCoverageEnd() == null
                || p.revenueCoverageEnd().isBefore(p.revenueCoverageStart())
                || (p.window().available() && p.window().hasNullBounds())
                || (p.window().available() && p.window().outside(
                        p.revenueCoverageStart(), p.revenueCoverageEnd()))
                || !"READY".equals(p.costState()) || p.costActiveToken() != null
                || p.costGenerationAt() == null || p.pairedCostGenerationAt() == null
                || !p.costGenerationAt().equals(p.pairedCostGenerationAt())
                || !Objects.equals(p.basisGenerationId(), p.costBasisGenerationId())
                || p.costPublicationVersion() == null) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
    }

    private void validateWatermarks(PublicationSnapshot p, Map<String, Watermark> live) {
        if (!live.keySet().equals(p.sourceVersions().keySet())) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        p.sourceVersions().forEach((name, version) -> {
            Watermark current = live.get(name);
            if (current == null || !"READY".equals(current.state()) || !version.equals(current.version())) {
                throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
            }
        });
    }

    private CostRequestCertification validateCertifiedCostRequest(PublicationSnapshot p) {
        return validateCertifiedCostRequest(p, QUERY_TIMEOUT_MS);
    }

    private CostRequestCertification validateCertifiedCostRequest(PublicationSnapshot p, int queryTimeoutMs) {
        if (p.latestRequestId() == null || p.certifiedRequestId() == null
                || !p.latestRequestId().equals(p.certifiedRequestId())
                || !Objects.equals(p.latestRequestVector(), p.certifiedRequestVector())) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        Query query = em.createNativeQuery(LATEST_REQUEST_SQL);
        query.setParameter("requestId", p.latestRequestId());
        query.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        if (rows.size() != 1) throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        Object[] row = rows.getFirst();
        String status = str(row[1]);
        if (!("READY".equals(status) || "NO_CHANGE".equals(status))
                || !Objects.equals(str(row[3]), p.certifiedRequestVector())
                || row[8] != null) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        if ("READY".equals(status)
                && (!Objects.equals(instant(row[4]), p.costGenerationAt())
                || !Objects.equals(str(row[6]), p.costBasisGenerationId()))) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        if ("NO_CHANGE".equals(status)
                && (!Objects.equals(instant(row[5]), p.costGenerationAt())
                || !Objects.equals(str(row[7]), p.costBasisGenerationId()))) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        return new CostRequestCertification(
                bi(row[0]), status, str(row[2]), str(row[3]),
                instant(row[4]), instant(row[5]), str(row[6]), str(row[7]));
    }

    private static boolean windowMatchesCost(
            SelectedWindow window, PracticeCostSnapshotProvider.CanonicalSnapshot cost) {
        return monthKey(window.anchor()).equals(cost.reportingThroughMonthKey())
                && monthKey(window.currentStart()).equals(cost.currentPeriodStartMonthKey())
                && monthKey(window.currentEnd()).equals(cost.currentPeriodEndMonthKey())
                && monthKey(window.priorStart()).equals(cost.priorPeriodStartMonthKey())
                && monthKey(window.priorEnd()).equals(cost.priorPeriodEndMonthKey());
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PracticeContributionReadTransactionTimeoutException
                    || current instanceof SQLTimeoutException
                    || current.getClass().getSimpleName().contains("QueryTimeout")
                    || current.getClass().getSimpleName().contains("TransactionTimeout")) return true;
        }
        return false;
    }

    private static Map<String, String> stringifyVersions(Map<String, BigInteger> versions) {
        Map<String, String> result = new LinkedHashMap<>();
        versions.forEach((key, value) -> result.put(key, value.toString()));
        return result;
    }

    private static String moneyDelta(BigDecimal current, BigDecimal prior, boolean c, boolean p) {
        return c && p && current != null && prior != null ? money(current.subtract(prior)) : null;
    }

    private static String pctDelta(BigDecimal current, BigDecimal prior, boolean c, boolean p) {
        if (!c || !p || current == null || prior == null || prior.signum() == 0) return null;
        return decimalString(current.subtract(prior).divide(prior.abs(), MathContext.DECIMAL128)
                .multiply(BigDecimal.valueOf(100)));
    }

    private static String pointDelta(BigDecimal current, BigDecimal prior, boolean c, boolean p) {
        return c && p && current != null && prior != null ? decimalString(current.subtract(prior)) : null;
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String decimalString(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.setScale(4, RoundingMode.HALF_UP);
        return normalized.signum() == 0 ? "0.0000" : normalized.toPlainString();
    }

    private static String pct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return decimalString(numerator.divide(denominator, MathContext.DECIMAL128)
                .multiply(BigDecimal.valueOf(100)));
    }

    private static BigDecimal decimal(Double value) {
        return value == null || !Double.isFinite(value) ? null : BigDecimal.valueOf(value);
    }

    private static BigDecimal bd(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(String.valueOf(value));
    }

    private static int integer(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.intValue() : new BigInteger(String.valueOf(value)).intValueExact();
    }

    private static BigInteger bi(Object value) {
        BigInteger result = biNullable(value);
        return result == null ? BigInteger.ZERO : result;
    }

    private static BigInteger biNullable(Object value) {
        if (value == null) return null;
        if (value instanceof BigInteger integer) return integer;
        if (value instanceof BigDecimal decimal) return decimal.toBigIntegerExact();
        if (value instanceof Number number) return new BigInteger(number.toString());
        return new BigInteger(String.valueOf(value));
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String str(Object value) { return value == null ? null : String.valueOf(value); }

    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        return Instant.parse(String.valueOf(value));
    }

    private static String month(LocalDate value) { return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy-MM")); }
    private static String monthKey(LocalDate value) { return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyyMM")); }

    @FunctionalInterface
    private interface QueryTimeoutBudget {
        int remainingMillis();
    }

    /**
     * Named projection over the {@link #loadPeriod} allocation aggregation columns. Confirmed
     * coverage uses {@link #confirmedGlAbs()} (CONFIRMED_GL valuation only) while the broader
     * attributed coverage uses {@link #usableAbs()} (CONFIRMED_GL plus provisional valuations).
     */
    record AllocationRow(Object[] cells) {
        String segment() { return String.valueOf(cells[0]); }
        String attributionStatus() { return String.valueOf(cells[1]); }
        BigDecimal confirmedGlSigned() { return bd(cells[2]); }
        BigDecimal provisionalSigned() { return bd(cells[3]); }
        BigDecimal usableAbs() { return bd(cells[4]); }
        BigDecimal provisionalAbs() { return bd(cells[5]); }
        BigDecimal confirmedGlAbs() { return bd(cells[6]); }
        int provisionalItemCount() { return integer(cells[7]); }
        int registeredValueCount() { return integer(cells[9]); }
        int registeredHoursCount() { return integer(cells[10]); }
        int scheduledCapacityCount() { return integer(cells[11]); }
        int monthEndCount() { return integer(cells[12]); }
        int historicalFallbackCount() { return integer(cells[13]); }
    }

    record Watermark(BigInteger version, String state) {}

    record CoherenceToken(PublicationSnapshot publication, Map<String, Watermark> watermarks,
                          CostRequestCertification request) {
        CoherenceToken {
            watermarks = Map.copyOf(watermarks);
        }
    }

    record ReadAttempt(CoherenceToken token, PracticeContributionResponseDTO response) {}

    record CostRequestCertification(BigInteger requestId, String status, String requestKey,
                                    String inputVectorFingerprint, Instant resultingCostGenerationAt,
                                    Instant comparedCostGenerationAt, String resultingBasisGenerationId,
                                    String comparedBasisGenerationId) {}

    record SelectedWindow(boolean available, String reason, LocalDate anchor, LocalDate currentStart,
                          LocalDate currentEnd, LocalDate priorStart, LocalDate priorEnd) {
        boolean hasNullBounds() {
            return anchor == null || currentStart == null || currentEnd == null || priorStart == null || priorEnd == null;
        }

        boolean outside(LocalDate coverageStart, LocalDate coverageEnd) {
            return priorStart.isBefore(coverageStart) || currentEnd.isAfter(coverageEnd);
        }
    }

    record PublicationSnapshot(boolean servingEnabled, BigInteger controlVersion, String status,
                               String generationId, Instant pairedCostGenerationAt, String basisGenerationId,
                               BigInteger fullBiVersion, Map<String, BigInteger> sourceVersions,
                               SelectedWindow window, Instant publishedAt, Instant refreshedAt,
                               BigInteger publicationVersion, String costState, String costActiveToken,
                               Instant costGenerationAt, Instant costPublishedAt, String costBasisGenerationId,
                               BigInteger latestRequestId, String latestRequestVector,
                               BigInteger certifiedRequestId, String certifiedRequestVector,
                               LocalDate revenueCoverageStart, LocalDate revenueCoverageEnd,
                               BigInteger costPublicationVersion) {
        PublicationSnapshot {
            sourceVersions = Map.copyOf(sourceVersions);
        }
        boolean basesAligned() { return Objects.equals(basisGenerationId, costBasisGenerationId); }
        LocalDate coverageStart() { return revenueCoverageStart; }
    }

    record EvidenceScopeKey(boolean resolved, String segment) { }

    record EvidenceScopeSummary(int missingControlCount, int unresolvedEvidenceGapCount,
                                Map<String, Integer> scopedEvidenceGapCounts,
                                int duplicateRiskCount, int unresolvedDuplicateRiskCount,
                                Map<String, Integer> scopedDuplicateRiskCounts,
                                Map<String, BigDecimal> missingNativeAmounts) {
        EvidenceScopeSummary {
            scopedEvidenceGapCounts = immutableCounts(scopedEvidenceGapCounts);
            scopedDuplicateRiskCounts = immutableCounts(scopedDuplicateRiskCounts);
            Map<String, BigDecimal> nativeCopy = new LinkedHashMap<>();
            missingNativeAmounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> nativeCopy.put(entry.getKey(), entry.getValue()));
            missingNativeAmounts = java.util.Collections.unmodifiableMap(nativeCopy);
        }
        private static Map<String, Integer> immutableCounts(Map<String, Integer> source) {
            Map<String, Integer> copy = new LinkedHashMap<>();
            source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> copy.put(entry.getKey(), entry.getValue()));
            return java.util.Collections.unmodifiableMap(copy);
        }
    }

    record SegmentAggregate(BigDecimal authoritative, BigDecimal provisional, BigDecimal confirmed,
                            BigDecimal estimated, BigDecimal unassigned, BigDecimal partialAffected,
                            BigDecimal totalAbs, BigDecimal confirmedAttributedAbs,
                            BigDecimal attributedAbs, BigDecimal estimatedAbs,
                            BigDecimal unassignedAbs, BigDecimal partialAffectedAbs,
                            BigDecimal provisionalAbs, int provisionalCount, Set<String> reasons) {
        SegmentAggregate {
            reasons = Set.copyOf(reasons);
        }
        SegmentAggregate(BigDecimal authoritative, BigDecimal provisional, BigDecimal confirmed,
                         BigDecimal estimated, BigDecimal unassigned, BigDecimal partialAffected,
                         BigDecimal totalAbs, BigDecimal confirmedAttributedAbs,
                         BigDecimal attributedAbs, BigDecimal estimatedAbs,
                         BigDecimal unassignedAbs, BigDecimal partialAffectedAbs) {
            this(authoritative, provisional, confirmed, estimated, unassigned, partialAffected,
                    totalAbs, confirmedAttributedAbs, attributedAbs, estimatedAbs,
                    unassignedAbs, partialAffectedAbs, provisional.abs(),
                    provisional.signum() == 0 ? 0 : 1, Set.of());
        }
        static final SegmentAggregate ZERO = new SegmentAggregate(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, Set.of());
    }

    static final class MutableSegment {
        BigDecimal authoritative = BigDecimal.ZERO;
        BigDecimal provisional = BigDecimal.ZERO;
        BigDecimal confirmed = BigDecimal.ZERO;
        BigDecimal estimated = BigDecimal.ZERO;
        BigDecimal unassigned = BigDecimal.ZERO;
        BigDecimal partialAffected = BigDecimal.ZERO;
        BigDecimal totalAbs = BigDecimal.ZERO;
        BigDecimal confirmedAttributedAbs = BigDecimal.ZERO;
        BigDecimal attributedAbs = BigDecimal.ZERO;
        BigDecimal estimatedAbs = BigDecimal.ZERO;
        BigDecimal unassignedAbs = BigDecimal.ZERO;
        BigDecimal partialAffectedAbs = BigDecimal.ZERO;
        BigDecimal provisionalAbs = BigDecimal.ZERO;
        int provisionalCount;
        final Set<String> reasons = new LinkedHashSet<>();
        SegmentAggregate freeze() {
            return new SegmentAggregate(authoritative, provisional, confirmed, estimated, unassigned,
                    partialAffected, totalAbs, confirmedAttributedAbs, attributedAbs,
                    estimatedAbs, unassignedAbs, partialAffectedAbs, provisionalAbs, provisionalCount,
                    reasons);
        }
    }

    record PeriodAggregate(Map<String, SegmentAggregate> bySegment, int sourceDocumentCount,
                           int sourceItemCount, int valuedItemCount, int missingCount,
                           int duplicateRiskCount, BigDecimal glControl, BigDecimal glAllocated,
                           int glControlledDocumentCount, BigDecimal allocated,
                           BigDecimal reconciliationDifference, BigDecimal confirmed, BigDecimal estimated,
                           BigDecimal unassigned, BigDecimal partialAffected, BigDecimal totalAbs,
                           int registeredValueCount,
                           int registeredHoursCount, int scheduledCapacityCount, int monthEndCount,
                           int historicalFallbackCount, int provisionalNativeCount,
                           int provisionalMonthlyFxCount, Set<String> reasons,
                           int unresolvedEvidenceGapCount,
                           Map<String, Integer> scopedEvidenceGapCounts,
                           int unresolvedDuplicateRiskCount,
                           Map<String, Integer> scopedDuplicateRiskCounts,
                           Map<String, BigDecimal> missingNativeAmounts,
                           boolean sharedUnresolvedAttribution) {
        PeriodAggregate {
            bySegment = Map.copyOf(bySegment);
            reasons = Set.copyOf(reasons);
            scopedEvidenceGapCounts = Map.copyOf(scopedEvidenceGapCounts);
            scopedDuplicateRiskCounts = Map.copyOf(scopedDuplicateRiskCounts);
            missingNativeAmounts = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(missingNativeAmounts));
        }
        PeriodAggregate(Map<String, SegmentAggregate> bySegment, int sourceDocumentCount,
                        int sourceItemCount, int valuedItemCount, int missingCount,
                        int duplicateRiskCount, BigDecimal glControl, BigDecimal glAllocated,
                        int glControlledDocumentCount, BigDecimal allocated,
                        BigDecimal reconciliationDifference, BigDecimal confirmed, BigDecimal estimated,
                        BigDecimal unassigned, BigDecimal partialAffected, BigDecimal totalAbs,
                        int registeredValueCount, int registeredHoursCount, int scheduledCapacityCount,
                        int monthEndCount, int historicalFallbackCount, int provisionalNativeCount,
                        int provisionalMonthlyFxCount) {
            this(bySegment, sourceDocumentCount, sourceItemCount, valuedItemCount, missingCount,
                    duplicateRiskCount, glControl, glAllocated, glControlledDocumentCount, allocated,
                    reconciliationDifference, confirmed, estimated, unassigned, partialAffected, totalAbs,
                    registeredValueCount, registeredHoursCount, scheduledCapacityCount, monthEndCount,
                    historicalFallbackCount, provisionalNativeCount, provisionalMonthlyFxCount, Set.of(),
                    missingCount, Map.of(), duplicateRiskCount, Map.of(), Map.of(), false);
        }
        PeriodAggregate(Map<String, SegmentAggregate> bySegment, int sourceDocumentCount,
                        int sourceItemCount, int valuedItemCount, int missingCount,
                        int duplicateRiskCount, BigDecimal glControl, BigDecimal glAllocated,
                        int glControlledDocumentCount, BigDecimal allocated,
                        BigDecimal reconciliationDifference, BigDecimal confirmed, BigDecimal estimated,
                        BigDecimal unassigned, BigDecimal partialAffected, BigDecimal totalAbs,
                        int registeredValueCount, int registeredHoursCount, int scheduledCapacityCount,
                        int monthEndCount, int historicalFallbackCount, int provisionalNativeCount,
                        int provisionalMonthlyFxCount, Set<String> reasons) {
            this(bySegment, sourceDocumentCount, sourceItemCount, valuedItemCount, missingCount,
                    duplicateRiskCount, glControl, glAllocated, glControlledDocumentCount, allocated,
                    reconciliationDifference, confirmed, estimated, unassigned, partialAffected, totalAbs,
                    registeredValueCount, registeredHoursCount, scheduledCapacityCount, monthEndCount,
                    historicalFallbackCount, provisionalNativeCount, provisionalMonthlyFxCount, reasons,
                    missingCount, Map.of(), duplicateRiskCount, Map.of(), Map.of(), false);
        }
        static PeriodAggregate unavailable() {
            return new PeriodAggregate(Map.of(), 0, 0, 0, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0, 0, 0, 0, 0, 0, 0, Set.of(), 1, Map.of(), 0, Map.of(), Map.of(), false);
        }
        SegmentAggregate segment(String id) { return bySegment.getOrDefault(id, SegmentAggregate.ZERO); }
        boolean hasProvisional() { return provisionalNativeCount > 0 || provisionalMonthlyFxCount > 0; }
        int evidenceGapCount() {
            return unresolvedEvidenceGapCount + scopedEvidenceGapCounts.values().stream()
                    .mapToInt(Integer::intValue).sum();
        }
        boolean hasBlockingEvidence() { return evidenceGapCount() > 0 || duplicateRiskCount > 0; }
        boolean hasBlockingEvidence(String id) {
            return unresolvedEvidenceGapCount > 0 || unresolvedDuplicateRiskCount > 0
                    || scopedEvidenceGapCounts.getOrDefault(id, 0) > 0
                    || scopedDuplicateRiskCounts.getOrDefault(id, 0) > 0;
        }
        // Serve-with-disclosed-gap policy (design owner, 2026-07-17): evidence gaps, provisional
        // valuations and blocking valuation reasons mark items that are EXCLUDED from every total,
        // so serving despite them understates with disclosure (sourceStatus/valuationStatus/
        // missing counts) — it can never overstate. Only duplicate risk (possible double count)
        // and a reconciliation break (allocation != GL control) can make numbers WRONG; those two
        // legs remain fail-closed.
        boolean revenueAvailable() {
            return duplicateRiskCount == 0 && reconciled();
        }
        boolean revenueAvailable(String id, SegmentAggregate segment) {
            return unresolvedDuplicateRiskCount == 0
                    && scopedDuplicateRiskCounts.getOrDefault(id, 0) == 0
                    && reconciled();
        }
        boolean reconciled() {
            BigDecimal tolerance = glControl.abs().multiply(new BigDecimal("0.0001")).max(BigDecimal.ONE);
            return reconciliationDifference.abs().compareTo(tolerance) <= 0;
        }
        boolean hasGlControlledSubset() { return glControlledDocumentCount > 0; }
        String reconciliationStatus() {
            if (!hasGlControlledSubset()) return "UNAVAILABLE";
            return reconciled() && !hasBlockingEvidence() && !hasProvisional()
                    ? "RECONCILED" : "PARTIAL_CONTROL";
        }
        boolean hasBusinessGaps() {
            return !revenueAvailable() || unassignedAbs().signum() != 0 || !"COMPLETE".equals(sourceStatus());
        }
        BigDecimal confirmedAbs() {
            return bySegment.values().stream().map(SegmentAggregate::confirmedAttributedAbs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal attributedAbs() {
            return bySegment.values().stream().map(SegmentAggregate::attributedAbs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal estimatedAbs() {
            return bySegment.values().stream().map(SegmentAggregate::estimatedAbs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal unassignedAbs() {
            return bySegment.values().stream().map(SegmentAggregate::unassignedAbs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal partialAffectedAbs() {
            return bySegment.values().stream().map(SegmentAggregate::partialAffectedAbs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal provisionalRevenue(String id, SegmentAggregate segment) {
            return segment.provisionalCount() > 0
                    && unresolvedDuplicateRiskCount == 0
                    && scopedDuplicateRiskCounts.getOrDefault(id, 0) == 0
                    ? segment.authoritative().add(segment.provisional()) : null;
        }
        String sourceStatus() { return sourceReason() != null || hasBlockingEvidence()
                ? "INCOMPLETE" : "COMPLETE"; }
        String sourceReason() {
            String reason = firstReason(reasons, SOURCE_REASON_PRECEDENCE);
            return reason != null ? reason : duplicateRiskCount > 0 ? "SOURCE_DUPLICATE_RISK"
                    : evidenceGapCount() > 0 ? "SOURCE_COUNT_INCOMPLETE" : null;
        }
        String sourceStatus(String id, SegmentAggregate segment) {
            return sourceReason(id, segment) == null && !hasBlockingEvidence(id) ? "COMPLETE" : "INCOMPLETE";
        }
        String sourceReason(String id, SegmentAggregate segment) {
            String scoped = firstReason(segment.reasons(), SOURCE_REASON_PRECEDENCE);
            if (scoped != null) return scoped;
            if (unresolvedDuplicateRiskCount > 0) {
                String global = firstReason(reasons, SOURCE_REASON_PRECEDENCE);
                return global != null ? global : "SOURCE_DUPLICATE_RISK";
            }
            if (scopedDuplicateRiskCounts.getOrDefault(id, 0) > 0) return "SOURCE_DUPLICATE_RISK";
            if (unresolvedEvidenceGapCount > 0) {
                String global = firstReason(reasons, SOURCE_REASON_PRECEDENCE);
                return global != null ? global : "SOURCE_COUNT_INCOMPLETE";
            }
            return scopedEvidenceGapCounts.getOrDefault(id, 0) > 0 ? "SOURCE_COUNT_INCOMPLETE" : null;
        }
        String fxStatus() {
            return hasBlockingEvidence() || blockingValuationReason(reasons) != null
                    ? "UNAVAILABLE" : hasProvisional() ? "PROVISIONAL" : "CONFIRMED_GL";
        }
        String fxReason() {
            String explicit = firstReason(reasons, VALUATION_REASON_PRECEDENCE);
            if (explicit != null && !"HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE".equals(explicit)) return explicit;
            if (evidenceGapCount() > 0 || duplicateRiskCount > 0) return explicit;
            if (provisionalMonthlyFxCount > 0) return "PROVISIONAL_MONTHLY_FX";
            return provisionalNativeCount > 0 ? "PROVISIONAL_NATIVE_DKK" : null;
        }
        String valuationStatus() { return fxStatus(); }
        String valuationReason() { return fxReason(); }
        String valuationStatus(String id, SegmentAggregate segment) {
            if (hasBlockingEvidence(id) || blockingValuationReason(segment.reasons()) != null) {
                return "UNAVAILABLE";
            }
            return segment.provisionalCount() > 0 ? "PROVISIONAL" : "CONFIRMED_GL";
        }
        String valuationReason(String id, SegmentAggregate segment) {
            String explicit = firstReason(segment.reasons(), VALUATION_REASON_PRECEDENCE);
            if (explicit != null && !"HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE".equals(explicit)) return explicit;
            if (hasBlockingEvidence(id)) {
                if (unresolvedEvidenceGapCount > 0 || unresolvedDuplicateRiskCount > 0) {
                    String global = firstReason(reasons, VALUATION_REASON_PRECEDENCE);
                    if (global != null && !"HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE".equals(global)) return global;
                }
                return explicit;
            }
            if (segment.provisionalCount() == 0) return null;
            if (provisionalMonthlyFxCount > 0) return "PROVISIONAL_MONTHLY_FX";
            return "PROVISIONAL_NATIVE_DKK";
        }
        String attributionStatus() {
            return unassignedAbs().signum() != 0 || partialAffectedAbs().signum() != 0 ? "PARTIAL"
                    : estimatedAbs().signum() != 0 ? "ESTIMATED" : "CONFIRMED";
        }
        String attributionReason() {
            String explicit = firstReason(reasons, ATTRIBUTION_REASON_PRECEDENCE);
            if (explicit != null) return explicit;
            return switch (attributionStatus()) {
                case "PARTIAL" -> "ATTRIBUTION_RESIDUAL_UNASSIGNED";
                case "ESTIMATED" -> "ATTRIBUTION_ESTIMATED";
                default -> null;
            };
        }
        String attributionStatus(SegmentAggregate segment) {
            if (segment.unassignedAbs().signum() != 0 || segment.partialAffectedAbs().signum() != 0) {
                return "PARTIAL";
            }
            return segment.estimatedAbs().signum() != 0 ? "ESTIMATED" : "CONFIRMED";
        }
        String attributionReason(SegmentAggregate segment) {
            String explicit = firstReason(segment.reasons(), ATTRIBUTION_REASON_PRECEDENCE);
            if (explicit != null) return explicit;
            return switch (attributionStatus(segment)) {
                case "PARTIAL" -> "ATTRIBUTION_RESIDUAL_UNASSIGNED";
                case "ESTIMATED" -> "ATTRIBUTION_ESTIMATED";
                default -> null;
            };
        }
        String coreAttributionStatus(SegmentAggregate segment) {
            return sharedUnresolvedAttribution ? "PARTIAL" : attributionStatus(segment);
        }
        String coreAttributionReason(SegmentAggregate segment) {
            return sharedUnresolvedAttribution
                    ? "ATTRIBUTION_RESIDUAL_UNASSIGNED" : attributionReason(segment);
        }
        String attributionExplanationCode() {
            return attributionExplanationCode(0);
        }
        String attributionExplanationCode(int costMonthEndFallbackCount) {
            int fallbacks = (historicalFallbackCount > 0 ? 1 : 0)
                    + (scheduledCapacityCount > 0 ? 1 : 0) + (monthEndCount > 0 ? 1 : 0)
                    + (costMonthEndFallbackCount > 0 ? 1 : 0);
            if (fallbacks == 0) return "NO_FALLBACK";
            if (fallbacks > 1) return "MULTIPLE_FALLBACK_METHODS";
            if (historicalFallbackCount > 0) return "HISTORICAL_PRACTICE_FALLBACK";
            if (scheduledCapacityCount > 0) return "REVENUE_SCHEDULED_CAPACITY_FALLBACK";
            if (monthEndCount > 0) return "REVENUE_MONTH_END_PRACTICE_FALLBACK";
            return "COST_MONTH_END_PRACTICE_FALLBACK";
        }
        String attributionExplanation() { return attributionExplanation(attributionExplanationCode()); }
        String attributionExplanation(String code) { return switch (code) {
            case "HISTORICAL_PRACTICE_FALLBACK" -> "Historical practice was unavailable, so the disclosed current-practice fallback was used.";
            case "REVENUE_SCHEDULED_CAPACITY_FALLBACK" -> "Revenue uses the dated scheduled-capacity fallback.";
            case "REVENUE_MONTH_END_PRACTICE_FALLBACK" -> "Revenue uses the month-end effective-practice fallback.";
            case "COST_MONTH_END_PRACTICE_FALLBACK" -> "Cost uses the month-end effective-practice fallback where scheduled capacity was unavailable.";
            case "MULTIPLE_FALLBACK_METHODS" -> "Revenue and cost use multiple disclosed fallback methods.";
            default -> "No revenue or cost fallback method was used.";
        }; }
        private static String blockingValuationReason(Set<String> reasons) {
            String reason = firstReason(reasons, VALUATION_REASON_PRECEDENCE);
            return reason == null || "HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE".equals(reason)
                    || "HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE".equals(reason)
                    || "GL_CONTROL_RESIDUAL_UNASSIGNED".equals(reason)
                    || "PROVISIONAL_MONTHLY_FX".equals(reason)
                    || "PROVISIONAL_NATIVE_DKK".equals(reason) ? null : reason;
        }
    }

    record CoreMetrics(PracticeContributionPracticeDTO.Metrics dto, BigDecimal revenue, BigDecimal cost,
                       BigDecimal contribution, BigDecimal margin, boolean deltaEligible, boolean costEligible) {}
    record SegmentMetrics(PracticeRevenueSegmentDTO.Metrics dto, BigDecimal display, boolean deltaEligible) {}
}
