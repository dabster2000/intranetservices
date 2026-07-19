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
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
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

import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_DISABLED;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_UNAVAILABLE;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.QUERY_TIMEOUT;

/** Coherent aggregate reader for published practice revenue and the canonical cost snapshot. */
@JBossLog
@ApplicationScoped
public class CxoPracticeContributionService {
    static final int QUERY_TIMEOUT_MS = 10_000;
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
                   p.coverage_start_month
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

    @Transactional(Transactional.TxType.REQUIRED)
    @TransactionConfiguration(timeout = 10)
    public PracticeContributionResponseDTO getContribution(CostSource requestedSource) {
        CostSource source = requestedSource == null ? CostSource.BOOKED : requestedSource;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                PublicationSnapshot before = loadPublicationSnapshot(source);
                validatePublication(before);
                Map<String, Watermark> beforeWatermarks = loadWatermarks();
                validateWatermarks(before, beforeWatermarks);
                CostRequestCertification beforeRequest = validateCertifiedCostRequest(before);

                PracticeCostSnapshotProvider.Snapshot cost = costSnapshotProvider.getSnapshot(source);
                PracticeContributionResponseDTO result = buildResponse(before, cost.response(), source);

                PublicationSnapshot after = loadPublicationSnapshot(source);
                Map<String, Watermark> afterWatermarks = loadWatermarks();
                validatePublication(after);
                validateWatermarks(after, afterWatermarks);
                CostRequestCertification afterRequest = validateCertifiedCostRequest(after);
                if (before.equals(after) && beforeWatermarks.equals(afterWatermarks)
                        && beforeRequest.equals(afterRequest)) return result;
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

    private PracticeContributionResponseDTO buildResponse(
            PublicationSnapshot publication,
            PracticeOperatingCostResponseDTO cost,
            CostSource source) {
        SelectedWindow window = publication.window();
        boolean costAvailable = cost != null && cost.reportingThroughMonthKey() != null;
        if (costAvailable && (!window.available() || !windowMatchesCost(window, cost))) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }

        PeriodAggregate current = costAvailable
                ? loadPeriod(publication.generationId(), window.currentStart(), window.currentEnd())
                : PeriodAggregate.unavailable();
        PeriodAggregate prior = costAvailable
                ? loadPeriod(publication.generationId(), window.priorStart(), window.priorEnd())
                : PeriodAggregate.unavailable();

        Map<String, PracticeOperatingCostDTO> costs = new HashMap<>();
        if (cost != null) cost.practices().forEach(row -> costs.put(row.practiceId(), row));

        List<PracticeContributionPracticeDTO> practices = new ArrayList<>(CORE.size());
        for (String id : CORE) {
            if (!costAvailable) {
                practices.add(new PracticeContributionPracticeDTO(
                        id, LABELS.get(id), null, null,
                        null, null, null, null, null, null, null));
                continue;
            }
            PracticeOperatingCostDTO costRow = costs.get(id);
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

    private CoreMetrics coreMetrics(String id, PeriodAggregate period, PracticeOperatingCostDTO costs,
                                    boolean current, boolean costComplete, boolean fteComplete,
                                    boolean basesAligned) {
        SegmentAggregate revenue = period.bySegment().getOrDefault(id, SegmentAggregate.ZERO);
        BigDecimal salary = costs == null ? null : decimal(current ? costs.currentSalaryDkk() : costs.priorSalaryDkk());
        BigDecimal opex = costs == null ? null : decimal(current ? costs.currentOpexDkk() : costs.priorOpexDkk());
        if (!costComplete) {
            salary = null;
            opex = null;
        }
        BigDecimal cost = salary == null || opex == null ? null : salary.add(opex);
        BigDecimal averageFte = costs == null ? null : decimal(current ? costs.currentAverageFte() : costs.priorAverageFte());
        BigDecimal costPerFte = costs == null ? null : decimal(current ? costs.currentCostPerFteDkk() : costs.priorCostPerFteDkk());
        if (!fteComplete) {
            averageFte = null;
            costPerFte = null;
        }

        boolean revenueAvailable = period.revenueAvailable(revenue);
        String valuationStatus = period.valuationStatus(revenue);
        String valuationReason = period.valuationReason(revenue);
        String attributionStatus = period.attributionStatus(revenue);
        String attributionReason = period.attributionReason(revenue);
        BigDecimal authoritativeRevenue = revenueAvailable ? revenue.authoritative() : null;
        BigDecimal contribution = authoritativeRevenue != null && costComplete && cost != null && basesAligned
                ? authoritativeRevenue.subtract(cost) : null;
        BigDecimal margin = contribution != null && authoritativeRevenue.signum() > 0
                ? contribution.divide(authoritativeRevenue, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)) : null;
        String status = !revenueAvailable ? "UNAVAILABLE_REVENUE"
                : !costComplete ? "UNAVAILABLE_COST"
                : !basesAligned ? "UNALIGNED_PRACTICE_BASIS"
                : "PARTIAL".equals(attributionStatus) ? "PARTIAL_ATTRIBUTION"
                : "ESTIMATED".equals(attributionStatus) ? "ESTIMATED_ATTRIBUTION" : "CONFIRMED";
        boolean deltaEligible = ("CONFIRMED".equals(status) || "ESTIMATED_ATTRIBUTION".equals(status))
                && "COMPLETE".equals(period.sourceStatus()) && "CONFIRMED_GL".equals(valuationStatus);

        PracticeContributionPracticeDTO.Metrics dto = new PracticeContributionPracticeDTO.Metrics(
                money(authoritativeRevenue), money(period.provisionalRevenue(revenue)), money(salary), money(opex), money(cost),
                money(contribution), decimalString(margin), decimalString(averageFte), money(costPerFte),
                money(revenue.confirmed()), money(revenue.estimated()), money(revenue.partialAffected()),
                money(BigDecimal.ZERO),
                period.sourceStatus(), period.sourceReason(), pct(revenue.attributedAbs(), revenue.totalAbs()),
                valuationStatus, valuationReason, attributionStatus,
                attributionReason, costComplete ? "COMPLETE" : "INCOMPLETE",
                costComplete ? null : "COST_EVIDENCE_INCOMPLETE",
                fteComplete ? "COMPLETE" : "INCOMPLETE",
                fteComplete ? null : "FTE_EVIDENCE_INCOMPLETE",
                costs != null ? 12 : 0,
                fteComplete ? 12 : 0, fteComplete ? 0 : 12,
                basesAligned ? "ALIGNED_EFFECTIVE_DATED" : "UNALIGNED",
                basesAligned ? null : "PRACTICE_BASIS_UNALIGNED",
                status, availabilityReason(status, period.sourceReason(), valuationReason, attributionReason));
        return new CoreMetrics(dto, authoritativeRevenue, cost, contribution, margin,
                deltaEligible, costComplete && cost != null);
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
        String valuationStatus = period.valuationStatus(value);
        String valuationReason = period.valuationReason(value);
        String attributionStatus = period.attributionStatus(value);
        String attributionReason = period.attributionReason(value);
        String displayStatus = !period.revenueAvailable(value) ? "UNAVAILABLE"
                : "PARTIAL".equals(attributionStatus) ? "PARTIAL"
                : "ESTIMATED".equals(attributionStatus) ? "ESTIMATED" : "CONFIRMED";
        BigDecimal display = "UNAVAILABLE".equals(displayStatus) ? null : value.authoritative();
        boolean deltaEligible = !"UNASSIGNED".equals(id)
                && ("CONFIRMED".equals(displayStatus) || "ESTIMATED".equals(displayStatus))
                && "COMPLETE".equals(period.sourceStatus()) && "CONFIRMED_GL".equals(valuationStatus);
        return new SegmentMetrics(new PracticeRevenueSegmentDTO.Metrics(
                money(display), displayStatus, money(period.revenueAvailable(value) ? value.authoritative() : null),
                money(period.provisionalRevenue(value)), money(value.confirmed()), money(value.estimated()),
                money(value.partialAffected()), money("UNASSIGNED".equals(id) ? value.unassigned() : BigDecimal.ZERO),
                period.sourceStatus(), period.sourceReason(), valuationStatus, valuationReason,
                attributionStatus, attributionReason, pct(value.attributedAbs(), value.totalAbs()),
                "UNAVAILABLE".equals(displayStatus)
                        ? availabilityReason("UNAVAILABLE_REVENUE", period.sourceReason(), valuationReason, attributionReason)
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
                                                    PracticeOperatingCostResponseDTO cost, boolean current,
                                                    boolean basesAligned) {
        boolean costComplete = cost != null && (current ? cost.currentCostComplete() : cost.priorCostComplete());
        boolean fteComplete = cost != null && (current ? cost.currentFteComplete() : cost.priorFteComplete());
        boolean anyCoreRevenueUnavailable = CORE.stream()
                .map(period::segment)
                .anyMatch(segment -> !period.revenueAvailable(segment));
        boolean anyCorePartial = CORE.stream()
                .map(period::segment)
                .map(period::attributionStatus)
                .anyMatch("PARTIAL"::equals);
        boolean anyCoreEstimated = CORE.stream()
                .map(period::segment)
                .map(period::attributionStatus)
                .anyMatch("ESTIMATED"::equals);
        String contributionStatus = anyCoreRevenueUnavailable ? "UNAVAILABLE_REVENUE"
                : !costComplete ? "UNAVAILABLE_COST"
                : !basesAligned ? "UNALIGNED_PRACTICE_BASIS"
                : anyCorePartial ? "PARTIAL_ATTRIBUTION"
                : anyCoreEstimated ? "ESTIMATED_ATTRIBUTION" : "CONFIRMED";
        PracticeContributionEvidenceDTO evidence = new PracticeContributionEvidenceDTO(
                period.sourceDocumentCount(), period.sourceItemCount(), period.valuedItemCount(),
                pct(BigDecimal.valueOf(period.valuedItemCount()), BigDecimal.valueOf(period.sourceItemCount())),
                period.missingCount(), Map.of(), money(period.confirmed()), money(period.estimated()),
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
                period.scheduledCapacityCount(), period.monthEndCount(), 0,
                period.historicalFallbackCount(), period.historicalFallbackCount() > 0,
                period.attributionExplanationCode(), period.attributionExplanation(),
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
                period.duplicateRiskCount(), period.missingCount(), Map.of(),
                money(period.hasGlControlledSubset() ? period.reconciliationDifference() : null),
                period.reconciliationStatus(),
                "PARTIAL_CONTROL".equals(period.reconciliationStatus())
                        ? "RECONCILIATION_PARTIAL_CONTROL"
                        : period.revenueAvailable() ? null : period.valuationReason());
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

    private PeriodAggregate loadPeriod(String generationId, LocalDate start, LocalDate end) {
        Query allocationQuery = em.createNativeQuery("""
                SELECT a.segment_id, a.attribution_status,
                       COALESCE(SUM(CASE WHEN i.valuation_status = 'CONFIRMED_GL' THEN a.allocation_dkk ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN i.control_source IN ('NATIVE_DKK','MONTHLY_FX') THEN a.allocation_dkk ELSE 0 END), 0),
                       COALESCE(SUM(ABS(CASE WHEN i.valuation_status IN
                           ('CONFIRMED_GL','PROVISIONAL_NATIVE_DKK','PROVISIONAL_MONTHLY_FX')
                           THEN a.allocation_dkk ELSE 0 END)), 0),
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
        bindPeriod(allocationQuery, generationId, start, end);
        @SuppressWarnings("unchecked") List<Object[]> allocationRows = allocationQuery.getResultList();

        Query evidenceQuery = em.createNativeQuery("""
                SELECT COUNT(DISTINCT source_document_uuid),
                       SUM(CASE WHEN row_kind = 'SOURCE_ITEM' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN row_kind = 'SOURCE_ITEM' AND
                           (item_control_dkk IS NOT NULL OR valuation_status = 'CONTROLLED_BY_DOCUMENT_RESIDUAL')
                           THEN 1 ELSE 0 END),
                       SUM(CASE WHEN row_kind = 'SOURCE_ITEM' AND item_control_dkk IS NULL
                           AND valuation_status <> 'CONTROLLED_BY_DOCUMENT_RESIDUAL' THEN 1 ELSE 0 END)
                         + SUM(CASE WHEN row_kind = 'DOCUMENT_EVIDENCE'
                           AND valuation_status NOT IN ('CONFIRMED_GL','PROVISIONAL_NATIVE_DKK','PROVISIONAL_MONTHLY_FX')
                           THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN duplicate_risk_status <> 'NONE' THEN source_document_uuid END),
                       COALESCE(SUM(CASE WHEN valuation_status = 'CONFIRMED_GL' THEN item_control_dkk ELSE 0 END),0),
                       COALESCE(SUM(CASE WHEN control_source = 'ECONOMIC_GL' AND row_kind <> 'DOCUMENT_EVIDENCE' THEN item_control_dkk ELSE 0 END),0),
                       SUM(CASE WHEN valuation_status = 'PROVISIONAL_NATIVE_DKK' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN valuation_status = 'PROVISIONAL_MONTHLY_FX' THEN 1 ELSE 0 END)
                FROM fact_practice_net_revenue_item_mat
                WHERE generation_id = :generationId
                  AND recognized_month BETWEEN :fromMonth AND :toMonth
                """);
        bindPeriod(evidenceQuery, generationId, start, end);
        @SuppressWarnings("unchecked") List<Object[]> evidenceRows = evidenceQuery.getResultList();
        Object[] evidence = evidenceRows.isEmpty() ? new Object[9] : evidenceRows.getFirst();

        Query reconciliationQuery = em.createNativeQuery("""
                SELECT COALESCE(SUM(control_dkk), 0), COALESCE(SUM(allocated_dkk), 0), COUNT(*)
                FROM (
                    SELECT i.source_document_uuid,
                           MAX(i.document_gl_revenue_dkk) AS control_dkk,
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
        bindPeriod(reconciliationQuery, generationId, start, end);
        Object[] reconciliation = (Object[]) reconciliationQuery.getSingleResult();

        Map<String, MutableSegment> mutable = new LinkedHashMap<>();
        int registered = 0, registeredHours = 0, scheduled = 0, monthEnd = 0, historical = 0;
        for (Object[] row : allocationRows) {
            String segment = String.valueOf(row[0]);
            String status = String.valueOf(row[1]);
            MutableSegment target = mutable.computeIfAbsent(segment, ignored -> new MutableSegment());
            BigDecimal authoritative = bd(row[2]);
            BigDecimal provisional = bd(row[3]);
            target.authoritative = target.authoritative.add(authoritative);
            target.provisional = target.provisional.add(provisional);
            target.totalAbs = target.totalAbs.add(bd(row[4]));
            if ("CONFIRMED".equals(status)) {
                target.confirmed = target.confirmed.add(authoritative);
                target.confirmedAttributedAbs = target.confirmedAttributedAbs.add(bd(row[4]));
                target.attributedAbs = target.attributedAbs.add(bd(row[4]));
            } else if ("ESTIMATED".equals(status)) {
                target.estimated = target.estimated.add(authoritative);
                target.estimatedAbs = target.estimatedAbs.add(bd(row[4]));
                target.attributedAbs = target.attributedAbs.add(bd(row[4]));
            } else {
                target.unassigned = target.unassigned.add(authoritative).add(provisional);
                target.unassignedAbs = target.unassignedAbs.add(bd(row[4]));
            }
            registered += integer(row[6]);
            registeredHours += integer(row[7]);
            scheduled += integer(row[8]);
            monthEnd += integer(row[9]);
            historical += integer(row[10]);
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
        bindPeriod(partialQuery, generationId, start, end);
        @SuppressWarnings("unchecked") List<Object[]> partialRows = partialQuery.getResultList();
        BigDecimal partialAffected = BigDecimal.ZERO;
        for (Object[] row : partialRows) {
            BigDecimal affectedControl = bd(row[1]);
            partialAffected = partialAffected.add(affectedControl);
            String segments = str(row[2]);
            if (segments == null) continue;
            for (String segment : segments.split(",")) {
                MutableSegment target = mutable.computeIfAbsent(segment, ignored -> new MutableSegment());
                target.partialAffected = target.partialAffected.add(affectedControl);
                target.partialAffectedAbs = target.partialAffectedAbs.add(affectedControl.abs());
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
                missing, integer(evidence[4]), gl, glAllocated, glDocumentCount, allocated, diff,
                confirmed, estimated, unassigned, partialAffected,
                totalAbs, registered, registeredHours, scheduled, monthEnd, historical,
                integer(evidence[7]), integer(evidence[8]));
    }

    private void bindPeriod(Query query, String generationId, LocalDate start, LocalDate end) {
        query.setParameter("generationId", generationId);
        query.setParameter("fromMonth", start);
        query.setParameter("toMonth", end);
        query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS);
    }

    private PublicationSnapshot loadPublicationSnapshot(CostSource source) {
        Query query = em.createNativeQuery(SNAPSHOT_SQL);
        query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS);
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
                date(r[42]));
    }

    private Map<String, Watermark> loadWatermarks() {
        Query query = em.createNativeQuery(WATERMARK_SQL);
        query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        Map<String, Watermark> result = new LinkedHashMap<>();
        for (Object[] row : rows) result.put(str(row[0]), new Watermark(bi(row[1]), str(row[2])));
        return result;
    }

    private void validatePublication(PublicationSnapshot p) {
        if (!p.servingEnabled()) throw new ContributionUnavailableException(PUBLICATION_DISABLED);
        if (!"READY".equals(p.status()) || p.generationId() == null || p.publishedAt() == null
                || (p.window().available() && p.window().hasNullBounds())
                || !"READY".equals(p.costState()) || p.costActiveToken() != null
                || p.costGenerationAt() == null || p.pairedCostGenerationAt() == null
                || !p.costGenerationAt().equals(p.pairedCostGenerationAt())
                || !Objects.equals(p.basisGenerationId(), p.costBasisGenerationId())) {
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
        if (p.latestRequestId() == null || p.certifiedRequestId() == null
                || !p.latestRequestId().equals(p.certifiedRequestId())
                || !Objects.equals(p.latestRequestVector(), p.certifiedRequestVector())) {
            throw new ContributionUnavailableException(PUBLICATION_UNAVAILABLE);
        }
        Query query = em.createNativeQuery(LATEST_REQUEST_SQL);
        query.setParameter("requestId", p.latestRequestId());
        query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS);
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

    private static boolean windowMatchesCost(SelectedWindow window, PracticeOperatingCostResponseDTO cost) {
        return monthKey(window.anchor()).equals(cost.reportingThroughMonthKey())
                && monthKey(window.currentStart()).equals(cost.currentPeriodStartMonthKey())
                && monthKey(window.currentEnd()).equals(cost.currentPeriodEndMonthKey())
                && monthKey(window.priorStart()).equals(cost.priorPeriodStartMonthKey())
                && monthKey(window.priorEnd()).equals(cost.priorPeriodEndMonthKey());
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException
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
        return decimalString(current.subtract(prior).divide(prior.abs(), 8, RoundingMode.HALF_UP)
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
        return decimalString(numerator.divide(denominator, 8, RoundingMode.HALF_UP)
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

    record Watermark(BigInteger version, String state) {}

    record CostRequestCertification(BigInteger requestId, String status, String requestKey,
                                    String inputVectorFingerprint, Instant resultingCostGenerationAt,
                                    Instant comparedCostGenerationAt, String resultingBasisGenerationId,
                                    String comparedBasisGenerationId) {}

    record SelectedWindow(boolean available, String reason, LocalDate anchor, LocalDate currentStart,
                          LocalDate currentEnd, LocalDate priorStart, LocalDate priorEnd) {
        boolean hasNullBounds() {
            return anchor == null || currentStart == null || currentEnd == null || priorStart == null || priorEnd == null;
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
                               LocalDate revenueCoverageStart) {
        PublicationSnapshot {
            sourceVersions = Map.copyOf(sourceVersions);
        }
        boolean basesAligned() { return Objects.equals(basisGenerationId, costBasisGenerationId); }
        LocalDate coverageStart() { return revenueCoverageStart; }
    }

    record SegmentAggregate(BigDecimal authoritative, BigDecimal provisional, BigDecimal confirmed,
                            BigDecimal estimated, BigDecimal unassigned, BigDecimal partialAffected,
                            BigDecimal totalAbs, BigDecimal confirmedAttributedAbs,
                            BigDecimal attributedAbs, BigDecimal estimatedAbs,
                            BigDecimal unassignedAbs, BigDecimal partialAffectedAbs) {
        static final SegmentAggregate ZERO = new SegmentAggregate(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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
        SegmentAggregate freeze() {
            return new SegmentAggregate(authoritative, provisional, confirmed, estimated, unassigned,
                    partialAffected, totalAbs, confirmedAttributedAbs, attributedAbs,
                    estimatedAbs, unassignedAbs, partialAffectedAbs);
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
                           int provisionalMonthlyFxCount) {
        PeriodAggregate {
            bySegment = Map.copyOf(bySegment);
        }
        static PeriodAggregate unavailable() {
            return new PeriodAggregate(Map.of(), 0, 0, 0, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0, 0, 0, 0, 0, 0, 0);
        }
        SegmentAggregate segment(String id) { return bySegment.getOrDefault(id, SegmentAggregate.ZERO); }
        boolean hasProvisional() { return provisionalNativeCount > 0 || provisionalMonthlyFxCount > 0; }
        boolean revenueAvailable() { return missingCount == 0 && !hasProvisional() && reconciled(); }
        boolean revenueAvailable(SegmentAggregate segment) {
            return missingCount == 0 && segment.provisional().signum() == 0 && reconciled();
        }
        boolean reconciled() {
            BigDecimal tolerance = glControl.abs().multiply(new BigDecimal("0.0001")).max(BigDecimal.ONE);
            return reconciliationDifference.abs().compareTo(tolerance) <= 0;
        }
        boolean hasGlControlledSubset() { return glControlledDocumentCount > 0; }
        String reconciliationStatus() {
            if (!hasGlControlledSubset()) return "UNAVAILABLE";
            return reconciled() && missingCount == 0 && !hasProvisional() ? "RECONCILED" : "PARTIAL_CONTROL";
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
        BigDecimal provisionalRevenue(SegmentAggregate segment) {
            return segment.provisional().signum() != 0 && missingCount == 0
                    ? segment.authoritative().add(segment.provisional()) : null;
        }
        String sourceStatus() { return duplicateRiskCount > 0 ? "INCOMPLETE" : "COMPLETE"; }
        String sourceReason() { return duplicateRiskCount > 0 ? "SOURCE_DUPLICATE_RISK" : null; }
        String fxStatus() { return missingCount > 0 ? "UNAVAILABLE" : hasProvisional() ? "PROVISIONAL" : "CONFIRMED_GL"; }
        String fxReason() {
            if (missingCount > 0) return "GL_CONTROL_MISSING";
            if (provisionalMonthlyFxCount > 0) return "PROVISIONAL_MONTHLY_FX";
            return provisionalNativeCount > 0 ? "PROVISIONAL_NATIVE_DKK" : null;
        }
        String valuationStatus() { return fxStatus(); }
        String valuationReason() { return fxReason(); }
        String valuationStatus(SegmentAggregate segment) {
            if (missingCount > 0) return "UNAVAILABLE";
            return segment.provisional().signum() != 0 ? "PROVISIONAL" : "CONFIRMED_GL";
        }
        String valuationReason(SegmentAggregate segment) {
            if (missingCount > 0) return "GL_CONTROL_MISSING";
            if (segment.provisional().signum() == 0) return null;
            if (provisionalMonthlyFxCount > 0) return "PROVISIONAL_MONTHLY_FX";
            return "PROVISIONAL_NATIVE_DKK";
        }
        String attributionStatus() {
            return unassignedAbs().signum() != 0 || partialAffectedAbs().signum() != 0 ? "PARTIAL"
                    : estimatedAbs().signum() != 0 ? "ESTIMATED" : "CONFIRMED";
        }
        String attributionReason() {
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
            return switch (attributionStatus(segment)) {
                case "PARTIAL" -> "ATTRIBUTION_RESIDUAL_UNASSIGNED";
                case "ESTIMATED" -> "ATTRIBUTION_ESTIMATED";
                default -> null;
            };
        }
        String attributionExplanationCode() {
            int fallbacks = (historicalFallbackCount > 0 ? 1 : 0)
                    + (scheduledCapacityCount > 0 ? 1 : 0) + (monthEndCount > 0 ? 1 : 0);
            if (fallbacks == 0) return "NO_FALLBACK";
            if (fallbacks > 1) return "MULTIPLE_FALLBACK_METHODS";
            if (historicalFallbackCount > 0) return "HISTORICAL_PRACTICE_FALLBACK";
            if (scheduledCapacityCount > 0) return "REVENUE_SCHEDULED_CAPACITY_FALLBACK";
            return "REVENUE_MONTH_END_PRACTICE_FALLBACK";
        }
        String attributionExplanation() { return switch (attributionExplanationCode()) {
            case "HISTORICAL_PRACTICE_FALLBACK" -> "Historical practice was unavailable, so the disclosed current-practice fallback was used.";
            case "REVENUE_SCHEDULED_CAPACITY_FALLBACK" -> "Revenue uses the dated scheduled-capacity fallback.";
            case "REVENUE_MONTH_END_PRACTICE_FALLBACK" -> "Revenue uses the month-end effective-practice fallback.";
            case "MULTIPLE_FALLBACK_METHODS" -> "Revenue uses multiple disclosed fallback methods.";
            default -> "No revenue or cost fallback method was used.";
        }; }
    }

    record CoreMetrics(PracticeContributionPracticeDTO.Metrics dto, BigDecimal revenue, BigDecimal cost,
                       BigDecimal contribution, BigDecimal margin, boolean deltaEligible, boolean costEligible) {}
    record SegmentMetrics(PracticeRevenueSegmentDTO.Metrics dto, BigDecimal display, boolean deltaEligible) {}
}
