package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPeriodDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPracticeDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticePortfolioReconciliationDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeRevenueSegmentDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_DISABLED;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.PUBLICATION_UNAVAILABLE;
import static dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException.QUERY_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CxoPracticeContributionServiceTest {

    private static final Instant COST_GENERATION = Instant.parse("2026-07-15T08:00:00Z");
    private static final String BASIS = "basis-v1";
    private static final String VECTOR = "vector-v1";

    @Test
    void fixedOrderingLabelsAndNoAnchorUseNullRatherThanFalseZero() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        PracticeContributionResponseDTO response = invoke(service, "buildResponse",
                new Class<?>[]{CxoPracticeContributionService.PublicationSnapshot.class,
                        PracticeOperatingCostResponseDTO.class, CostSource.class},
                snapshot(false, BigInteger.ONE), unavailableCost(), CostSource.BOOKED);

        assertEquals("UNAVAILABLE_COST", response.responseStatus());
        assertEquals("SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW", response.responseReason());
        assertNull(response.reportingThroughMonth());
        assertNull(response.currentPeriod());
        assertNull(response.priorPeriod());
        assertNull(response.currentPortfolio());
        assertNull(response.priorPortfolio());
        assertEquals(LocalDate.of(2020, 1, 1), response.revenueHistoryCoverageStart());
        assertEquals(List.of("PM", "BA", "CYB", "DEV", "SA"),
                response.practices().stream().map(PracticeContributionPracticeDTO::practiceId).toList());
        assertEquals(List.of("Project Management", "Business Analysis", "Cybersecurity", "Technology",
                        "Solution Architecture"),
                response.practices().stream().map(PracticeContributionPracticeDTO::label).toList());
        assertEquals(List.of("JK", "UD", "EXTERNAL", "OTHER", "UNASSIGNED"),
                response.revenueOnlySegments().stream().map(PracticeRevenueSegmentDTO::segmentId).toList());
        assertTrue(response.practices().stream().allMatch(row -> row.current() == null && row.prior() == null
                && row.revenueDeltaDkk() == null && row.costDeltaDkk() == null
                && row.contributionDeltaDkk() == null));
        assertTrue(response.revenueOnlySegments().stream().allMatch(row -> row.current() == null
                && row.prior() == null && row.revenueDeltaDkk() == null && row.revenueDeltaPct() == null));
    }

    @Test
    void requestedSourceAvailabilityReasonMustMatchTheCertifiedCostPointer() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        PracticeOperatingCostResponseDTO unavailable = unavailableCost();
        CxoPracticeContributionService.PublicationSnapshot publication = snapshot(false, BigInteger.ONE);
        CxoPracticeContributionService.PublicationSnapshot mismatched =
                new CxoPracticeContributionService.PublicationSnapshot(
                        publication.servingEnabled(), publication.controlVersion(), publication.status(),
                        publication.generationId(), publication.pairedCostGenerationAt(),
                        publication.basisGenerationId(), publication.fullBiVersion(),
                        publication.sourceVersions(),
                        new CxoPracticeContributionService.SelectedWindow(false,
                                "DIFFERENT_UNAVAILABLE_REASON", null, null, null, null, null),
                        publication.publishedAt(), publication.refreshedAt(),
                        publication.publicationVersion(), publication.costState(),
                        publication.costActiveToken(), publication.costGenerationAt(),
                        publication.costPublishedAt(), publication.costBasisGenerationId(),
                        publication.latestRequestId(), publication.latestRequestVector(),
                        publication.certifiedRequestId(), publication.certifiedRequestVector(),
                        publication.revenueCoverageStart(), publication.revenueCoverageEnd(),
                        publication.costPublicationVersion());

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> invoke(service, "buildResponse",
                        new Class<?>[]{CxoPracticeContributionService.PublicationSnapshot.class,
                                PracticeOperatingCostResponseDTO.class, CostSource.class},
                        mismatched, unavailable, CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
    }

    @Test
    void selectedCostWindowOutsideRevenueCoverageFailsClosed() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PublicationSnapshot publication = snapshot(true, BigInteger.ONE);
        assertDoesNotThrow(() -> invoke(service, "validatePublication",
                new Class<?>[]{CxoPracticeContributionService.PublicationSnapshot.class}, publication));
        CxoPracticeContributionService.PublicationSnapshot truncatedRevenue =
                new CxoPracticeContributionService.PublicationSnapshot(
                        publication.servingEnabled(), publication.controlVersion(), publication.status(),
                        publication.generationId(), publication.pairedCostGenerationAt(),
                        publication.basisGenerationId(), publication.fullBiVersion(),
                        publication.sourceVersions(), publication.window(), publication.publishedAt(),
                        publication.refreshedAt(), publication.publicationVersion(), publication.costState(),
                        publication.costActiveToken(), publication.costGenerationAt(),
                        publication.costPublishedAt(), publication.costBasisGenerationId(),
                        publication.latestRequestId(), publication.latestRequestVector(),
                        publication.certifiedRequestId(), publication.certifiedRequestVector(),
                        publication.revenueCoverageStart(), LocalDate.of(2026, 5, 1),
                        publication.costPublicationVersion());

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> invoke(service, "validatePublication",
                        new Class<?>[]{CxoPracticeContributionService.PublicationSnapshot.class},
                        truncatedRevenue));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
    }

    @Test
    void rowScopedResidualDoesNotContaminateUnrelatedCoreOrRevenueOnlyRows() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        Map<String, CxoPracticeContributionService.SegmentAggregate> segments = new LinkedHashMap<>();
        segments.put("PM", confirmed("100.00"));
        segments.put("JK", confirmed("40.00"));
        segments.put("UNASSIGNED", partial("20.00"));
        CxoPracticeContributionService.PeriodAggregate period = period(segments, 0, 0, 0, 0,
                1, 0, 0, 0, 0);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.SegmentMetrics jk = invoke(service, "segmentMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class}, "JK", period);

        assertEquals("CONFIRMED", pm.dto().contributionStatus());
        assertEquals("CONFIRMED", pm.dto().attributionStatus());
        assertEquals("100.00", pm.dto().netAttributedRevenueDkk());
        assertEquals("50.00", pm.dto().contributionDkk());
        assertNull(pm.dto().availabilityReason());
        assertEquals("CONFIRMED", jk.dto().revenueDisplayStatus());
        assertEquals("40.00", jk.dto().displayRevenueDkk());
        assertNull(jk.dto().availabilityReason());
    }

    @Test
    void knownPracticeValuationGapSuppressesOnlyThatPracticeAndThePortfolio() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = scopedPeriod(
                Map.of("PM", confirmed("100.00"), "BA", confirmed("200.00")),
                1, 0, 0, Map.of("PM", 1), 0, Map.of(), Map.of("EUR", new BigDecimal("75")), false);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.CoreMetrics ba = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "BA", period, costRow("BA", 30, 20, 25, 15), true, true, true, true);
        PracticePortfolioReconciliationDTO portfolio = invoke(service, "portfolio",
                new Class<?>[]{CxoPracticeContributionService.PeriodAggregate.class}, period);
        PracticeContributionPeriodDTO contributionPeriod = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1),
                period, completeCost(), true, true);

        // Serve-with-disclosed-gap: a scoped evidence gap discloses (INCOMPLETE source, missing
        // native amounts, no deltas) but no longer withholds the understated GL-confirmed number.
        assertEquals("SOURCE_COUNT_INCOMPLETE", pm.dto().sourceReason());
        assertEquals("INCOMPLETE", pm.dto().sourceStatus());
        assertEquals("100.00", pm.dto().netAttributedRevenueDkk());
        assertFalse(pm.deltaEligible(), "a practice with an evidence gap never serves deltas");
        assertEquals("CONFIRMED", ba.dto().contributionStatus());
        assertEquals("COMPLETE", ba.dto().sourceStatus());
        assertEquals("200.00", ba.dto().netAttributedRevenueDkk());
        assertTrue(ba.deltaEligible(), "the unrelated practice remains delta-eligible");
        assertEquals("300.00", portfolio.recognizedNetRevenueDkk());
        assertEquals(Map.of("EUR", "75"), portfolio.missingNativeAmountsByCurrency());
        assertEquals(Map.of("EUR", "75"),
                contributionPeriod.evidence().missingNativeAmountsByCurrency());
    }

    @Test
    void unresolvedValuationGapSuppressesEveryCorePractice() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = scopedPeriod(
                Map.of("PM", confirmed("100.00"), "BA", confirmed("200.00")),
                1, 0, 1, Map.of(), 0, Map.of(), Map.of(), false);

        for (String practice : List.of("PM", "BA", "CYB", "DEV", "SA")) {
            CxoPracticeContributionService.CoreMetrics metrics = invoke(service, "coreMetrics",
                    new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                            PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                    practice, period, costRow(practice, 30, 20, 25, 15), true, true, true, true);
            // Serve-with-disclosed-gap: an unscopeable gap taints every practice's completeness
            // disclosure and suppresses deltas, but the GL-confirmed numbers still serve.
            assertNotEquals("UNAVAILABLE_REVENUE", metrics.dto().contributionStatus(), practice);
            assertNotNull(metrics.dto().netAttributedRevenueDkk(), practice);
            assertEquals("INCOMPLETE", metrics.dto().sourceStatus(), practice);
            assertFalse(metrics.deltaEligible(), practice);
        }
    }

    @Test
    void knownDuplicateRiskIsDistinctPerDocumentAndScopedToItsPractice() {
        CxoPracticeContributionService.EvidenceScopeSummary summary =
                CxoPracticeContributionService.summarizeEvidenceScope(List.of(
                        evidenceRow("SOURCE_ITEM", "duplicate-doc", "UNAVAILABLE_DUPLICATE_RISK",
                                "PHANTOM_DUPLICATE_RISK", "DKK", "10", null, "PM", "RESOLVED"),
                        evidenceRow("SOURCE_ITEM", "duplicate-doc", "UNAVAILABLE_DUPLICATE_RISK",
                                "PHANTOM_DUPLICATE_RISK", "DKK", "20", null, "PM", "RESOLVED")));
        CxoPracticeContributionService.PeriodAggregate period = scopedPeriod(
                Map.of("PM", confirmed("100.00"), "BA", confirmed("200.00")),
                2, 1, 0, Map.of("PM", 2), 0, summary.scopedDuplicateRiskCounts(),
                summary.missingNativeAmounts(), false);
        CxoPracticeContributionService service = new CxoPracticeContributionService();

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.CoreMetrics ba = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "BA", period, costRow("BA", 30, 20, 25, 15), true, true, true, true);

        assertEquals(1, summary.duplicateRiskCount());
        assertEquals(Map.of("PM", 1), summary.scopedDuplicateRiskCounts());
        assertEquals("UNAVAILABLE_REVENUE", pm.dto().contributionStatus());
        assertEquals("SOURCE_DUPLICATE_RISK", pm.dto().sourceReason());
        assertEquals("CONFIRMED", ba.dto().contributionStatus());
        assertTrue(ba.deltaEligible());
    }

    @Test
    void whollyUnassignedValuedResidualMarksEveryCorePartialWithoutDuplicatingAmounts() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = scopedPeriod(
                Map.of("UNASSIGNED", partial("20.00")),
                0, 0, 0, Map.of(), 0, Map.of(), Map.of(), true,
                Set.of("GL_CONTROL_RESIDUAL_UNASSIGNED"));

        for (String practice : List.of("PM", "BA", "CYB", "DEV", "SA")) {
            CxoPracticeContributionService.CoreMetrics metrics = invoke(service, "coreMetrics",
                    new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                            PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                    practice, period, costRow(practice, 0, 0, 0, 0), true, true, true, true);
            assertEquals("PARTIAL_ATTRIBUTION", metrics.dto().contributionStatus(), practice);
            assertEquals("CONFIRMED_GL", metrics.dto().valuationStatus(), practice);
            assertEquals("0.00", metrics.dto().netAttributedRevenueDkk(), practice);
            assertEquals("0.00", metrics.dto().partialAttributionAffectedRevenueDkk(), practice);
            assertEquals("0.00", metrics.dto().unassignedRevenueDkk(), practice);
            assertFalse(metrics.deltaEligible(), practice);
        }
        CxoPracticeContributionService.PeriodAggregate headerResidual = scopedPeriod(
                Map.of("UNASSIGNED", partial("20.00")),
                0, 0, 0, Map.of(), 0, Map.of(), Map.of(), true,
                Set.of("HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE"));
        assertTrue(headerResidual.revenueAvailable());
        assertEquals("CONFIRMED_GL", headerResidual.fxStatus());
    }

    @Test
    void missingNativeEvidenceIsSignedScopedAndExcludesControlledResidualAndSentinelRows() {
        CxoPracticeContributionService.EvidenceScopeSummary summary =
                CxoPracticeContributionService.summarizeEvidenceScope(List.of(
                        evidenceRow("SOURCE_ITEM", "doc-1", "UNAVAILABLE_MISSING", "NONE",
                                "eur", "100.000000000000", null, "PM", "RESOLVED"),
                        evidenceRow("SOURCE_ITEM", "doc-2", "UNAVAILABLE_MISSING", "NONE",
                                "EUR", "-25.000000000000", null, "PM", "RESOLVED"),
                        evidenceRow("SOURCE_ITEM", "doc-3", "CONTROLLED_BY_DOCUMENT_RESIDUAL", "NONE",
                                "EUR", "999.000000000000", null, "PM", "RESOLVED"),
                        evidenceRow("DOCUMENT_EVIDENCE", "doc-4", "UNAVAILABLE_MISSING", "NONE",
                                null, null, null, "BA", "RESOLVED")));

        assertEquals(2, summary.missingControlCount());
        assertEquals(Map.of("EUR", new BigDecimal("75.000000000000")), summary.missingNativeAmounts());
        assertEquals(Map.of("BA", 1, "PM", 2), summary.scopedEvidenceGapCounts());
        assertEquals(0, summary.unresolvedEvidenceGapCount());
        assertThrows(UnsupportedOperationException.class,
                () -> summary.missingNativeAmounts().put("USD", BigDecimal.ONE));
    }

    @Test
    void zeroItemSentinelScopesAvailabilityWithoutInventingAMissingItem() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = scopedPeriod(
                Map.of("PM", confirmed("100.00"), "BA", confirmed("200.00")),
                0, 0, 0, Map.of("BA", 1), 0, Map.of(), Map.of(), false);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.CoreMetrics ba = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "BA", period, costRow("BA", 30, 20, 25, 15), true, true, true, true);
        PracticeContributionPeriodDTO contributionPeriod = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1),
                period, completeCost(), true, true);

        assertEquals("CONFIRMED", pm.dto().contributionStatus());
        // Serve-with-disclosed-gap: the sentinel-scoped gap keeps BA's disclosure INCOMPLETE
        // without withholding its GL-confirmed number.
        assertEquals("200.00", ba.dto().netAttributedRevenueDkk());
        assertEquals("INCOMPLETE", ba.dto().sourceStatus());
        assertEquals("SOURCE_COUNT_INCOMPLETE", ba.dto().sourceReason());
        assertFalse(ba.deltaEligible());
        assertEquals(0, contributionPeriod.evidence().missingDkkControlCount());
        assertEquals("SOURCE_COUNT_INCOMPLETE", contributionPeriod.sourceReason());
    }

    @Test
    void incompletePriorCostDoesNotSuppressAValidCurrentContribution() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = period(
                Map.of("PM", confirmed("100.00")), 0, 0, 0, 0,
                0, 0, 0, 0, 0);
        PracticeOperatingCostDTO cost = costRow("PM", 30, 20, 25, 15);

        CxoPracticeContributionService.CoreMetrics current = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, cost, true, true, true, true);
        CxoPracticeContributionService.CoreMetrics prior = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, cost, false, false, true, true);

        assertEquals("CONFIRMED", current.dto().contributionStatus());
        assertEquals("50.00", current.dto().contributionDkk());
        assertEquals("UNAVAILABLE_COST", prior.dto().contributionStatus());
        assertEquals("100.00", prior.dto().netAttributedRevenueDkk());
        assertNull(prior.dto().operatingCostDkk());
        assertNull(prior.dto().contributionDkk());
        assertNull(invokeStatic("moneyDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                current.contribution(), prior.contribution(), current.deltaEligible(), prior.deltaEligible()));
    }

    @Test
    void provisionalRevenueIsEvidenceOnlyAndScopedToItsSegment() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        Map<String, CxoPracticeContributionService.SegmentAggregate> segments = Map.of(
                "PM", confirmed("100.00"),
                "JK", provisional("25.00"));
        CxoPracticeContributionService.PeriodAggregate period = period(segments, 0, 0, 1, 0,
                0, 0, 0, 0, 0);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.SegmentMetrics jk = invoke(service, "segmentMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class}, "JK", period);
        PracticeContributionPeriodDTO contributionPeriod = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1), period, completeCost(), true, true);

        assertEquals("CONFIRMED", pm.dto().contributionStatus());
        assertEquals("100.00", pm.dto().netAttributedRevenueDkk());
        assertNull(pm.dto().provisionalNetAttributedRevenueDkk());
        // Serve-with-disclosed-gap: the provisional-only segment serves its GL-confirmed headline
        // (zero) while the provisional value stays in its own disclosed field.
        assertNotEquals("UNAVAILABLE", jk.dto().revenueDisplayStatus());
        assertEquals("0.00", jk.dto().netAttributedRevenueDkk());
        assertEquals("25.00", jk.dto().provisionalNetAttributedRevenueDkk());
        assertEquals("PROVISIONAL", jk.dto().valuationStatus());
        assertEquals("PROVISIONAL_NATIVE_DKK", jk.dto().valuationReason());
        assertEquals("CONFIRMED", contributionPeriod.contributionStatus(),
                "Revenue-only provisional evidence must not change the core-practice contribution status");
        assertEquals("PROVISIONAL", contributionPeriod.fxStatus());
    }

    @Test
    void cancellingProvisionalMovementNeverBecomesConfirmedZero() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.SegmentAggregate cancelled =
                new CxoPracticeContributionService.SegmentAggregate(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("200.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("200.00"), 2, Set.of());
        CxoPracticeContributionService.PeriodAggregate period =
                new CxoPracticeContributionService.PeriodAggregate(
                        Map.of("PM", cancelled), 2, 2, 2, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("200.00"),
                        0, 0, 0, 0, 0, 2, 0, Set.of("PROVISIONAL_NATIVE_DKK"));

        CxoPracticeContributionService.CoreMetrics metrics = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);

        // Serve-with-disclosed-gap: the cancelled movement serves a zero headline, but never as a
        // CONFIRMED_GL zero — the PROVISIONAL valuation status and suppressed deltas carry the
        // original intent that cancellation is not confirmation.
        assertEquals("PROVISIONAL", metrics.dto().valuationStatus());
        assertEquals("0.00", metrics.dto().netAttributedRevenueDkk());
        assertEquals("0.00", metrics.dto().provisionalNetAttributedRevenueDkk());
        assertFalse(metrics.deltaEligible());
    }

    @Test
    void persistedInternalReasonsMapToClosedPublicPrecedence() {
        assertEquals("SOURCE_CLASSIFICATION_UNAVAILABLE", invokeStatic("publicReason",
                new Class<?>[]{String.class}, "ITEM_CLASSIFICATION_UNAVAILABLE"));
        assertEquals("HEADER_DISCOUNT_EVIDENCE_UNAVAILABLE", invokeStatic("publicReason",
                new Class<?>[]{String.class}, "HEADER_DISCOUNT_PROVENANCE_UNAVAILABLE"));
        assertEquals("PHANTOM_DUPLICATE_RISK", invokeStatic("publicReason",
                new Class<?>[]{String.class}, "MANUAL_PHANTOM_DUPLICATE_RISK"));
        assertEquals("CONSULTANT_TYPE_UNAVAILABLE", invokeStatic("publicReason",
                new Class<?>[]{String.class}, "MISSING_CONSULTANT_TYPE"));
        assertEquals("SOURCE_COUNT_INCOMPLETE", invokeStatic("publicReason",
                new Class<?>[]{String.class}, "ZERO_ITEM_DOCUMENT"));
    }

    @Test
    void signedCancellationCannotHideEstimatedOrUnassignedExposure() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = period(Map.of(
                        "PM", cancelledEstimated("200.00"),
                        "JK", cancelledUnassigned("200.00")),
                0, 0, 0, 0, 0, 0, 0, 0, 0);

        CxoPracticeContributionService.CoreMetrics pm = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);
        CxoPracticeContributionService.SegmentMetrics jk = invoke(service, "segmentMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class}, "JK", period);
        PracticeContributionPeriodDTO dto = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1),
                period, completeCost(), true, true);

        assertEquals("ESTIMATED_ATTRIBUTION", pm.dto().contributionStatus());
        assertEquals("ESTIMATED", pm.dto().attributionStatus());
        assertEquals("PARTIAL", jk.dto().revenueDisplayStatus());
        assertEquals("PARTIAL", jk.dto().attributionStatus());
        assertEquals("ESTIMATED_ATTRIBUTION", dto.contributionStatus(),
                "Revenue-only unassigned evidence must not contaminate the core contribution status");
        assertEquals("50.0000", dto.evidence().unassignedCoveragePct());
        assertEquals("0.00", dto.evidence().unassignedRevenueDkk());
    }

    @Test
    void unavailableReasonUsesSourceBeforeValuationAndNeverInventsZero() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.PeriodAggregate period = period(Map.of("PM", confirmed("100.00")),
                1, 1, 0, 0, 0, 0, 0, 0, 0);
        CxoPracticeContributionService.CoreMetrics metrics = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period, costRow("PM", 30, 20, 25, 15), true, true, true, true);

        assertEquals("UNAVAILABLE_REVENUE", metrics.dto().contributionStatus());
        assertEquals("SOURCE_DUPLICATE_RISK", metrics.dto().availabilityReason());
        assertNull(metrics.dto().netAttributedRevenueDkk());
        assertNull(metrics.dto().contributionDkk());
        assertEquals("100.00", metrics.dto().confirmedAttributedRevenueDkk());
    }

    @Test
    void fallbackExplanationsIgnoreRoutineRegisteredEvidenceAndCloseToSixCodes() {
        assertEquals("NO_FALLBACK", period(Map.of(), 0, 0, 0, 0, 4, 3, 0, 0, 0)
                .attributionExplanationCode());
        assertEquals("HISTORICAL_PRACTICE_FALLBACK", period(Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 2)
                .attributionExplanationCode());
        assertEquals("REVENUE_SCHEDULED_CAPACITY_FALLBACK", period(Map.of(), 0, 0, 0, 0, 0, 0, 2, 0, 0)
                .attributionExplanationCode());
        assertEquals("REVENUE_MONTH_END_PRACTICE_FALLBACK", period(Map.of(), 0, 0, 0, 0, 0, 0, 0, 2, 0)
                .attributionExplanationCode());
        assertEquals("MULTIPLE_FALLBACK_METHODS", period(Map.of(), 0, 0, 0, 0, 0, 0, 2, 0, 1)
                .attributionExplanationCode());
        assertEquals("COST_MONTH_END_PRACTICE_FALLBACK",
                period(Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
                        .attributionExplanationCode(2));
    }

    @Test
    void deltaMathPreservesSignsAndWithholdsOnlyPriorZeroPercentage() {
        assertEquals("25.00", invokeStatic("moneyDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("125"), new BigDecimal("100"), true, true));
        assertEquals("25.0000", invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("125"), new BigDecimal("100"), true, true));
        assertEquals("25.0000", invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("-75"), new BigDecimal("-100"), true, true));
        assertNull(invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                BigDecimal.ZERO, BigDecimal.ZERO, true, true));
        assertNull(invokeStatic("moneyDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                BigDecimal.ONE, BigDecimal.ONE, true, false));
    }

    @Test
    void percentageMathRoundsOnlyOnceAtTheFinalFourDecimalBoundary() {
        assertEquals("50.4950", invokeStatic("pct",
                new Class<?>[]{BigDecimal.class, BigDecimal.class},
                new BigDecimal("51"), new BigDecimal("101")));
        assertEquals("50.4950", invokeStatic("pctDelta",
                new Class<?>[]{BigDecimal.class, BigDecimal.class, boolean.class, boolean.class},
                new BigDecimal("152"), new BigDecimal("101"), true, true));

        CxoPracticeContributionService service = new CxoPracticeContributionService();
        CxoPracticeContributionService.CoreMetrics metrics = invoke(service, "coreMetrics",
                new Class<?>[]{String.class, CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostDTO.class, boolean.class, boolean.class, boolean.class, boolean.class},
                "PM", period(Map.of("PM", confirmed("101.00")), 0, 0, 0, 0,
                        0, 0, 0, 0, 0),
                costRow("PM", 30, 20, 25, 15), true, true, true, true);

        assertEquals("50.4950", metrics.dto().contributionMarginPct());
    }

    @Test
    void latestCertifiedRequestMustBeTerminalAndStableAcrossDoubleRead() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE),
                        snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE)),
                List.of(readyRequestRow(), noChangeRequestRow(), readyRequestRow(), noChangeRequestRow()));

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, times(2))
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void coherentDoubleReadReturnsStructuredUnavailableCostResponse() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE)),
                List.of(readyRequestRow(), readyRequestRow()));

        PracticeContributionResponseDTO response = service.getContribution(CostSource.BOOKED);

        assertEquals("UNAVAILABLE_COST", response.responseStatus());
        verify(service.costSnapshotProvider)
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void changedFirstReadRetriesInFreshTransactionsAndReturnsOnlyTheSecondGeneration() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE, "generation-v1"),
                        snapshotRow(BigInteger.TWO, "generation-v2"),
                        snapshotRow(BigInteger.TWO, "generation-v2"),
                        snapshotRow(BigInteger.TWO, "generation-v2")),
                List.of(readyRequestRow(), readyRequestRow(), readyRequestRow(), readyRequestRow()));
        RecordingReadTransactions transactions = new RecordingReadTransactions();
        service.readTransactions = transactions;

        PracticeContributionResponseDTO response = service.getContribution(CostSource.BOOKED);

        assertEquals("generation-v2", response.revenueGenerationId());
        assertEquals(4, transactions.timeouts().size(),
                "Each body and after guard must run in its own fresh transaction");
        verify(service.costSnapshotProvider, times(2))
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void advancedSourceWatermarkRetriesAndReturnsOnlyTheSecondCoherentVector() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE, BigInteger.ONE),
                        snapshotRow(BigInteger.ONE, BigInteger.TWO),
                        snapshotRow(BigInteger.ONE, BigInteger.TWO),
                        snapshotRow(BigInteger.ONE, BigInteger.TWO)),
                List.of(readyRequestRow(), readyRequestRow(), readyRequestRow(), readyRequestRow()));
        useWatermarkSequence(service, BigInteger.ONE, BigInteger.TWO, BigInteger.TWO, BigInteger.TWO);

        PracticeContributionResponseDTO response = service.getContribution(CostSource.BOOKED);

        assertTrue(response.sourceWatermarkVersions().values().stream().allMatch("2"::equals));
        verify(service.costSnapshotProvider, times(2))
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void secondSourceWatermarkAdvanceFailsClosedAfterOneRetry() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE, BigInteger.ONE),
                        snapshotRow(BigInteger.ONE, BigInteger.TWO),
                        snapshotRow(BigInteger.ONE, BigInteger.TWO),
                        snapshotRow(BigInteger.ONE, BigInteger.valueOf(3))),
                List.of(readyRequestRow(), readyRequestRow(), readyRequestRow(), readyRequestRow()));
        useWatermarkSequence(service, BigInteger.ONE, BigInteger.TWO,
                BigInteger.TWO, BigInteger.valueOf(3));

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, times(2))
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void secondPublicationMismatchFailsClosedAfterOneRetry() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.TWO),
                        snapshotRow(BigInteger.valueOf(3)), snapshotRow(BigInteger.valueOf(4))),
                List.of(readyRequestRow(), readyRequestRow(), readyRequestRow(), readyRequestRow()));

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, times(2))
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void disabledPublicationAndAdvancedWatermarkNeverReachCostProvider() {
        CxoPracticeContributionService disabled = new CxoPracticeContributionService();
        disabled.em = queryManager(CxoPracticeContributionService.SNAPSHOT_SQL,
                Collections.singletonList(snapshotRow(false, BigInteger.ONE)));
        disabled.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        disabled.readTransactions = new RecordingReadTransactions();

        ContributionUnavailableException disabledError = assertThrows(ContributionUnavailableException.class,
                () -> disabled.getContribution(CostSource.BOOKED));
        assertEquals(PUBLICATION_DISABLED, disabledError.getMessage());
        verify(disabled.costSnapshotProvider, never())
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));

        CxoPracticeContributionService advanced = coherentNoAnchorService(
                Collections.singletonList(snapshotRow(BigInteger.ONE)), List.of());
        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        when(watermark.getResultList()).thenReturn(watermarkRows(BigInteger.TWO));
        when(advanced.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);

        ContributionUnavailableException advancedError = assertThrows(ContributionUnavailableException.class,
                () -> advanced.getContribution(CostSource.BOOKED));
        assertEquals(PUBLICATION_UNAVAILABLE, advancedError.getMessage());
        verify(advanced.costSnapshotProvider, never())
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void unexpectedSourceWatermarkFailsTheExactPublicationVectorClosed() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                Collections.singletonList(snapshotRow(BigInteger.ONE)), List.of());
        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        List<Object[]> rows = new ArrayList<>(watermarkRows(BigInteger.ONE));
        rows.add(new Object[]{"UNEXPECTED_SOURCE", BigInteger.ONE, "FAILED"});
        when(watermark.getResultList()).thenReturn(rows);
        when(service.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(PUBLICATION_UNAVAILABLE, error.getMessage());
        verify(service.costSnapshotProvider, never())
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void queryTimeoutMapsToOnlyTheSafeTimeoutCode() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = mock(EntityManager.class);
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        service.readTransactions = new RecordingReadTransactions();
        when(service.em.createNativeQuery(CxoPracticeContributionService.SNAPSHOT_SQL))
                .thenThrow(new QueryTimeoutFailure());

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(QUERY_TIMEOUT, error.getMessage());
        verify(service.costSnapshotProvider, never())
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void freshTransactionDeadlineFailureMapsToOnlyTheSafeTimeoutCode() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = mock(EntityManager.class);
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        service.readTransactions = new PracticeContributionReadTransactionRunner() {
            @Override
            public <T> T requiringNew(int timeoutSeconds, Supplier<T> work) {
                throw new PracticeContributionReadTransactionTimeoutException(new RuntimeException("rollback"));
            }
        };

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(QUERY_TIMEOUT, error.getMessage());
        verify(service.costSnapshotProvider, never())
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void invalidConfiguredRequestBudgetFailsClosedBeforeStartingAnyTransaction() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofMillis(10_001))) {
            CxoPracticeContributionService service = new CxoPracticeContributionService();
            service.em = mock(EntityManager.class);
            service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
            RecordingReadTransactions transactions = new RecordingReadTransactions();
            service.readTransactions = transactions;
            service.requestTimeout = invalid;

            ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                    () -> service.getContribution(CostSource.BOOKED));

            assertEquals(QUERY_TIMEOUT, error.getMessage());
            assertTrue(transactions.timeouts().isEmpty());
            verify(service.costSnapshotProvider, never())
                    .getSnapshot(any(CostSource.class), any(IntSupplier.class));
        }
    }

    @Test
    void oneMonotonicTenSecondBudgetBoundsEveryTransactionAndQuery() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                List.of(snapshotRow(BigInteger.ONE), snapshotRow(BigInteger.ONE)),
                List.of(readyRequestRow(), readyRequestRow()));
        RecordingReadTransactions transactions = new RecordingReadTransactions();
        service.readTransactions = transactions;
        service.monotonicNanos = sequencedNanos(
                0L,
                0L,
                TimeUnit.MILLISECONDS.toNanos(100),
                TimeUnit.MILLISECONDS.toNanos(200),
                TimeUnit.MILLISECONDS.toNanos(300),
                TimeUnit.MILLISECONDS.toNanos(400),
                TimeUnit.MILLISECONDS.toNanos(2_100),
                TimeUnit.MILLISECONDS.toNanos(2_200),
                TimeUnit.MILLISECONDS.toNanos(2_300),
                TimeUnit.MILLISECONDS.toNanos(2_400));

        PracticeContributionResponseDTO response = service.getContribution(CostSource.BOOKED);

        assertEquals("UNAVAILABLE_COST", response.responseStatus());
        assertEquals(List.of(10, 7), transactions.timeouts());
        verify(service.costSnapshotProvider)
                .getSnapshot(org.mockito.ArgumentMatchers.eq(CostSource.BOOKED), any(IntSupplier.class));
    }

    @Test
    void remainingBudgetBelowOneSecondFailsClosedBeforeStartingAnotherTransaction() {
        CxoPracticeContributionService service = coherentNoAnchorService(
                Collections.singletonList(snapshotRow(BigInteger.ONE)),
                Collections.singletonList(readyRequestRow()));
        RecordingReadTransactions transactions = new RecordingReadTransactions();
        service.readTransactions = transactions;
        service.monotonicNanos = sequencedNanos(0L, TimeUnit.MILLISECONDS.toNanos(9_001));

        ContributionUnavailableException error = assertThrows(ContributionUnavailableException.class,
                () -> service.getContribution(CostSource.BOOKED));

        assertEquals(QUERY_TIMEOUT, error.getMessage());
        assertTrue(transactions.timeouts().isEmpty());
        verify(service.costSnapshotProvider, never())
                .getSnapshot(any(CostSource.class), any(IntSupplier.class));
    }

    @Test
    void confirmedAttributedCoverageExcludesProvisionalWhileAttributedCoverageIncludesIt() {
        // One CONFIRMED-attribution segment carrying both a CONFIRMED_GL valuation (signed 100,
        // |100|) and a provisional native-DKK valuation (signed 25, |25|). Confirmed money and
        // confirmed coverage must reflect only the GL valuation; the broader attributed coverage
        // must include the provisional movement.
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = periodEm(
                List.<Object[]>of(allocationRow("PM", "CONFIRMED",
                        "100.00", "25.00", "125.00", "25.00", "100.00", 1, 2)),
                evidenceRow(1, 2, 2, 0, 0, 1, 0),
                reconciliationRow());

        CxoPracticeContributionService.PeriodAggregate period = loadPeriod(service);

        assertEquals(0, new BigDecimal("100.00").compareTo(period.confirmed()));
        assertEquals(0, new BigDecimal("100.00").compareTo(period.confirmedAbs()));
        assertEquals(0, new BigDecimal("125.00").compareTo(period.attributedAbs()));
        assertEquals(0, new BigDecimal("125.00").compareTo(period.totalAbs()));

        PracticePortfolioReconciliationDTO portfolio = invoke(service, "portfolio",
                new Class<?>[]{CxoPracticeContributionService.PeriodAggregate.class}, period);
        assertEquals("100.00", portfolio.confirmedAttributedRevenueDkk());
        assertEquals("80.0000", portfolio.confirmedAttributedCoveragePct());
        assertEquals("100.0000", portfolio.attributedCoveragePct());

        PracticeContributionPeriodDTO contributionPeriod = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1), period, completeCost(), true, true);
        assertEquals("100.00", contributionPeriod.evidence().confirmedAttributedRevenueDkk());
        assertEquals("80.0000", contributionPeriod.evidence().confirmedAttributedCoveragePct());
        assertEquals("100.0000", contributionPeriod.evidence().attributedCoveragePct());
    }

    @Test
    void zeroUsableDenominatorYieldsNullCoveragePercentages() {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = periodEm(
                List.<Object[]>of(allocationRow("PM", "CONFIRMED",
                        "0.00", "0.00", "0.00", "0.00", "0.00", 0, 1)),
                evidenceRow(1, 1, 1, 0, 0, 0, 0),
                reconciliationRow());

        CxoPracticeContributionService.PeriodAggregate period = loadPeriod(service);

        PracticePortfolioReconciliationDTO portfolio = invoke(service, "portfolio",
                new Class<?>[]{CxoPracticeContributionService.PeriodAggregate.class}, period);
        assertNull(portfolio.confirmedAttributedCoveragePct());
        assertNull(portfolio.attributedCoveragePct());

        PracticeContributionPeriodDTO contributionPeriod = invoke(service, "toPeriod",
                new Class<?>[]{LocalDate.class, LocalDate.class,
                        CxoPracticeContributionService.PeriodAggregate.class,
                        PracticeOperatingCostResponseDTO.class, boolean.class, boolean.class},
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1), period, completeCost(), true, true);
        assertNull(contributionPeriod.evidence().confirmedAttributedCoveragePct());
        assertNull(contributionPeriod.evidence().attributedCoveragePct());
    }

    @Test
    void priorPeriodCoverageDefectDoesNotSuppressAValidCurrentConfirmedCoverage() {
        // loadPeriod is evaluated per period; a zero-denominator (null-coverage) prior must not
        // change the confirmed coverage already computed for a valid current period.
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = periodEm(
                List.<Object[]>of(allocationRow("PM", "CONFIRMED",
                        "100.00", "25.00", "125.00", "25.00", "100.00", 1, 2)),
                evidenceRow(1, 2, 2, 0, 0, 1, 0),
                reconciliationRow());
        CxoPracticeContributionService.PeriodAggregate current = loadPeriod(service);

        service.em = periodEm(
                List.<Object[]>of(allocationRow("PM", "CONFIRMED",
                        "0.00", "0.00", "0.00", "0.00", "0.00", 0, 1)),
                evidenceRow(1, 1, 1, 0, 0, 0, 0),
                reconciliationRow());
        CxoPracticeContributionService.PeriodAggregate prior = loadPeriod(service);

        assertEquals("80.0000", invokeStatic("pct",
                new Class<?>[]{BigDecimal.class, BigDecimal.class},
                current.confirmedAbs(), current.totalAbs()));
        assertNull(invokeStatic("pct",
                new Class<?>[]{BigDecimal.class, BigDecimal.class},
                prior.confirmedAbs(), prior.totalAbs()));
    }

    private static CxoPracticeContributionService coherentNoAnchorService(
            List<Object[]> snapshots, List<Object[]> requests) {
        CxoPracticeContributionService service = new CxoPracticeContributionService();
        service.em = mock(EntityManager.class);
        service.costSnapshotProvider = mock(PracticeCostSnapshotProvider.class);
        service.readTransactions = new RecordingReadTransactions();
        List<Object[]> remainingSnapshots = new ArrayList<>(snapshots);
        List<Object[]> remainingRequests = new ArrayList<>(requests);

        Query snapshot = mock(Query.class);
        when(snapshot.setHint(any(String.class), any())).thenReturn(snapshot);
        when(snapshot.getResultList()).thenAnswer(
                invocation -> Collections.singletonList(remainingSnapshots.removeFirst()));
        when(service.em.createNativeQuery(CxoPracticeContributionService.SNAPSHOT_SQL)).thenReturn(snapshot);

        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        when(watermark.getResultList()).thenReturn(watermarkRows(BigInteger.ONE));
        when(service.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);

        Query request = mock(Query.class);
        when(request.setParameter(any(String.class), any())).thenReturn(request);
        when(request.setHint(any(String.class), any())).thenReturn(request);
        if (!remainingRequests.isEmpty()) {
            when(request.getResultList()).thenAnswer(
                    invocation -> Collections.singletonList(remainingRequests.removeFirst()));
        }
        when(service.em.createNativeQuery(CxoPracticeContributionService.LATEST_REQUEST_SQL)).thenReturn(request);
        when(service.costSnapshotProvider.getSnapshot(
                any(CostSource.class), any(IntSupplier.class))).thenAnswer(invocation -> {
                    invocation.<IntSupplier>getArgument(1).getAsInt();
                    return new PracticeCostSnapshotProvider.Snapshot(unavailableCost(), false);
                });
        return service;
    }

    private static EntityManager queryManager(String sql, List<Object[]> rows) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(query.setHint(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        when(em.createNativeQuery(sql)).thenReturn(query);
        return em;
    }

    private static List<Object[]> watermarkRows(BigInteger version) {
        return sourceVersions().keySet().stream()
                .map(name -> new Object[]{name, version, "READY"})
                .toList();
    }

    private static void useWatermarkSequence(
            CxoPracticeContributionService service, BigInteger... versions) {
        List<BigInteger> remaining = new ArrayList<>(List.of(versions));
        Query watermark = mock(Query.class);
        when(watermark.setHint(any(String.class), any())).thenReturn(watermark);
        when(watermark.getResultList()).thenAnswer(
                invocation -> watermarkRows(remaining.removeFirst()));
        when(service.em.createNativeQuery(CxoPracticeContributionService.WATERMARK_SQL)).thenReturn(watermark);
    }

    private static Object[] snapshotRow(BigInteger publicationVersion) {
        return snapshotRow(true, publicationVersion);
    }

    private static Object[] snapshotRow(BigInteger publicationVersion, String generationId) {
        Object[] row = snapshotRow(true, publicationVersion);
        row[3] = generationId;
        return row;
    }

    private static Object[] snapshotRow(BigInteger publicationVersion, BigInteger sourceVersion) {
        Object[] row = snapshotRow(true, publicationVersion);
        for (int i = 7; i <= 15; i++) row[i] = sourceVersion;
        return row;
    }

    private static Object[] snapshotRow(boolean serving, BigInteger publicationVersion) {
        Object[] row = new Object[45];
        row[0] = serving;
        row[1] = BigInteger.ONE;
        row[2] = "READY";
        row[3] = "revenue-generation";
        row[4] = COST_GENERATION;
        row[5] = BASIS;
        row[6] = BigInteger.valueOf(7);
        for (int i = 7; i <= 15; i++) row[i] = BigInteger.ONE;
        row[16] = false;
        row[17] = "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW";
        row[23] = false;
        row[24] = "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW";
        row[30] = Instant.parse("2026-07-15T09:00:00Z");
        row[31] = Instant.parse("2026-07-15T08:30:00Z");
        row[32] = publicationVersion;
        row[33] = "READY";
        row[35] = COST_GENERATION;
        row[36] = Instant.parse("2026-07-15T08:15:00Z");
        row[37] = BASIS;
        row[38] = BigInteger.ONE;
        row[39] = VECTOR;
        row[40] = BigInteger.ONE;
        row[41] = VECTOR;
        row[42] = LocalDate.of(2020, 1, 1);
        row[43] = LocalDate.of(2026, 6, 1);
        row[44] = BigInteger.ONE;
        return row;
    }

    private static Object[] readyRequestRow() {
        return new Object[]{BigInteger.ONE, "READY", "key", VECTOR,
                COST_GENERATION, null, BASIS, null, null};
    }

    private static Object[] noChangeRequestRow() {
        return new Object[]{BigInteger.ONE, "NO_CHANGE", "key", VECTOR,
                null, COST_GENERATION, null, BASIS, null};
    }

    private static CxoPracticeContributionService.PublicationSnapshot snapshot(
            boolean available, BigInteger publicationVersion) {
        LocalDate anchor = available ? LocalDate.of(2026, 6, 1) : null;
        return new CxoPracticeContributionService.PublicationSnapshot(
                true, BigInteger.ONE, "READY", "revenue-generation", COST_GENERATION, BASIS,
                BigInteger.valueOf(7), sourceVersions(),
                new CxoPracticeContributionService.SelectedWindow(available,
                        available ? null : "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW",
                        anchor, available ? LocalDate.of(2025, 7, 1) : null,
                        available ? LocalDate.of(2026, 6, 1) : null,
                        available ? LocalDate.of(2024, 7, 1) : null,
                        available ? LocalDate.of(2025, 6, 1) : null),
                Instant.parse("2026-07-15T09:00:00Z"), Instant.parse("2026-07-15T08:30:00Z"),
                publicationVersion, "READY", null, COST_GENERATION,
                Instant.parse("2026-07-15T08:15:00Z"), BASIS,
                BigInteger.ONE, VECTOR, BigInteger.ONE, VECTOR,
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 6, 1), BigInteger.ONE);
    }

    private static Map<String, BigInteger> sourceVersions() {
        Map<String, BigInteger> versions = new LinkedHashMap<>();
        for (String name : List.of("INVOICE_DOCUMENT", "FINANCE_GL", "CURRENCY",
                "ACCOUNT_CLASSIFICATION", "INVOICE_ATTRIBUTION", "SELF_BILLED",
                "PHANTOM_ATTRIBUTION", "DELIVERY_EVIDENCE", "PRACTICE_BASIS_INPUT")) {
            versions.put(name, BigInteger.ONE);
        }
        return versions;
    }

    private static PracticeOperatingCostResponseDTO completeCost() {
        return costResponse(true, "202606", List.of(costRow("PM", 30, 20, 25, 15)));
    }

    private static PracticeOperatingCostResponseDTO unavailableCost() {
        return costResponse(false, null, List.of());
    }

    private static PracticeOperatingCostResponseDTO costResponse(
            boolean complete, String anchor, List<PracticeOperatingCostDTO> rows) {
        int cells = complete ? 60 : 0;
        return new PracticeOperatingCostResponseDTO(
                "BOOKED", anchor,
                complete ? "202507" : null, complete ? "202606" : null,
                complete ? "202407" : null, complete ? "202506" : null,
                complete ? Instant.parse("2026-07-15T08:00:00Z") : null,
                complete ? 12 : 0, complete ? 12 : 0, complete ? 12 : 0,
                complete ? 12 : 0, complete ? 12 : 0, complete ? 12 : 0,
                cells, cells, cells, 0, 0,
                cells, cells, cells, 0, 0,
                cells, cells, 0, cells, cells, 0,
                complete, complete, complete ? "COMPLETE" : "UNAVAILABLE",
                complete, complete, complete ? "COMPLETE" : "UNAVAILABLE",
                complete ? "COMPLETE" : "UNAVAILABLE", complete,
                "EFFECTIVE_DATED_PRACTICE", complete ? LocalDate.of(2020, 1, 1) : null,
                null, rows);
    }

    private static PracticeOperatingCostDTO costRow(String id, double currentSalary, double currentOpex,
                                                    double priorSalary, double priorOpex) {
        double current = currentSalary + currentOpex;
        double prior = priorSalary + priorOpex;
        return new PracticeOperatingCostDTO(id, currentSalary, currentOpex, current,
                priorSalary, priorOpex, prior, current - prior,
                prior == 0 ? null : (current - prior) / Math.abs(prior) * 100,
                1.0, 1.0, current, prior, current - prior,
                prior == 0 ? null : (current - prior) / Math.abs(prior) * 100);
    }

    private static CxoPracticeContributionService.SegmentAggregate confirmed(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new CxoPracticeContributionService.SegmentAggregate(value, BigDecimal.ZERO, value,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, value.abs(), value.abs(), value.abs(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CxoPracticeContributionService.SegmentAggregate provisional(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new CxoPracticeContributionService.SegmentAggregate(BigDecimal.ZERO, value, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, value.abs(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CxoPracticeContributionService.SegmentAggregate partial(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new CxoPracticeContributionService.SegmentAggregate(value, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, value, value, value.abs(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, value.abs(), value.abs());
    }

    private static CxoPracticeContributionService.SegmentAggregate cancelledEstimated(String absolute) {
        BigDecimal exposure = new BigDecimal(absolute);
        return new CxoPracticeContributionService.SegmentAggregate(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, exposure, BigDecimal.ZERO, exposure,
                exposure, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CxoPracticeContributionService.SegmentAggregate cancelledUnassigned(String absolute) {
        BigDecimal exposure = new BigDecimal(absolute);
        return new CxoPracticeContributionService.SegmentAggregate(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, exposure, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, exposure, exposure);
    }

    private static CxoPracticeContributionService.PeriodAggregate period(
            Map<String, CxoPracticeContributionService.SegmentAggregate> segments,
            int missing, int duplicate, int provisionalNative, int provisionalMonthly,
            int registeredValue, int registeredHours, int scheduled, int monthEnd, int historical) {
        BigDecimal confirmed = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::confirmed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimated = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::estimated)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unassigned = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::unassigned)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal partial = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::partialAffected)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAbs = segments.values().stream().map(CxoPracticeContributionService.SegmentAggregate::totalAbs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal authoritative = segments.values().stream()
                .map(CxoPracticeContributionService.SegmentAggregate::authoritative)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CxoPracticeContributionService.PeriodAggregate(segments, 1, 1, missing == 0 ? 1 : 0,
                missing, duplicate, authoritative, authoritative, 1, authoritative, BigDecimal.ZERO,
                confirmed, estimated, unassigned, partial, totalAbs,
                registeredValue, registeredHours, scheduled, monthEnd, historical,
                provisionalNative, provisionalMonthly);
    }

    private static CxoPracticeContributionService.PeriodAggregate scopedPeriod(
            Map<String, CxoPracticeContributionService.SegmentAggregate> segments,
            int missing, int duplicate, int unresolvedEvidence,
            Map<String, Integer> scopedEvidence, int unresolvedDuplicate,
            Map<String, Integer> scopedDuplicate, Map<String, BigDecimal> nativeAmounts,
            boolean sharedUnresolvedAttribution) {
        return scopedPeriod(segments, missing, duplicate, unresolvedEvidence, scopedEvidence,
                unresolvedDuplicate, scopedDuplicate, nativeAmounts, sharedUnresolvedAttribution, Set.of());
    }

    private static CxoPracticeContributionService.PeriodAggregate scopedPeriod(
            Map<String, CxoPracticeContributionService.SegmentAggregate> segments,
            int missing, int duplicate, int unresolvedEvidence,
            Map<String, Integer> scopedEvidence, int unresolvedDuplicate,
            Map<String, Integer> scopedDuplicate, Map<String, BigDecimal> nativeAmounts,
            boolean sharedUnresolvedAttribution, Set<String> reasons) {
        CxoPracticeContributionService.PeriodAggregate base = period(
                segments, missing, duplicate, 0, 0, 0, 0, 0, 0, 0);
        return new CxoPracticeContributionService.PeriodAggregate(
                base.bySegment(), base.sourceDocumentCount(), base.sourceItemCount(), base.valuedItemCount(),
                base.missingCount(), base.duplicateRiskCount(), base.glControl(), base.glAllocated(),
                base.glControlledDocumentCount(), base.allocated(), base.reconciliationDifference(),
                base.confirmed(), base.estimated(), base.unassigned(), base.partialAffected(), base.totalAbs(),
                base.registeredValueCount(), base.registeredHoursCount(), base.scheduledCapacityCount(),
                base.monthEndCount(), base.historicalFallbackCount(), base.provisionalNativeCount(),
                base.provisionalMonthlyFxCount(), reasons, unresolvedEvidence, scopedEvidence,
                unresolvedDuplicate, scopedDuplicate, nativeAmounts, sharedUnresolvedAttribution);
    }

    private static Object[] evidenceRow(
            String rowKind, String documentUuid, String valuationStatus, String duplicateRiskStatus,
            String currency, String signedNativeControl, String itemControl,
            String segment, String scopeStatus) {
        return new Object[]{rowKind, documentUuid, valuationStatus, duplicateRiskStatus, currency,
                signedNativeControl == null ? null : new BigDecimal(signedNativeControl),
                itemControl == null ? null : new BigDecimal(itemControl), segment, scopeStatus};
    }

    private static CxoPracticeContributionService.PeriodAggregate loadPeriod(
            CxoPracticeContributionService service) {
        return invoke(service, "loadPeriod",
                new Class<?>[]{String.class, LocalDate.class, LocalDate.class, queryTimeoutBudgetType()},
                "generation", LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 1), queryTimeoutBudget());
    }

    private static EntityManager periodEm(
            List<Object[]> allocation, Object[] evidence, Object[] reconciliation) {
        EntityManager em = mock(EntityManager.class);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            Query query = mock(Query.class);
            if (sql.contains("missing_dkk_control_count")) {
                when(query.getResultList()).thenReturn(Collections.singletonList(evidence));
            } else if (sql.contains("scope_resolution_status")) {
                when(query.getResultList()).thenReturn(Collections.emptyList());
            } else if (sql.contains("controlled_documents")) {
                when(query.getSingleResult()).thenReturn(reconciliation);
            } else if (sql.contains("GROUP_CONCAT")) {
                when(query.getResultList()).thenReturn(Collections.emptyList());
            } else if (sql.contains("validation_reason_code")) {
                when(query.getResultList()).thenReturn(Collections.emptyList());
            } else if (sql.contains("GROUP BY a.segment_id, a.attribution_status")) {
                when(query.getResultList()).thenReturn(allocation);
            } else {
                when(query.getResultList()).thenReturn(Collections.emptyList());
            }
            return query;
        });
        return em;
    }

    private static Object[] allocationRow(String segment, String attributionStatus,
            String confirmedGlSigned, String provisionalSigned, String usableAbs,
            String provisionalAbs, String confirmedGlAbs, int provisionalItemCount, int rowCount) {
        return new Object[]{segment, attributionStatus,
                new BigDecimal(confirmedGlSigned), new BigDecimal(provisionalSigned),
                new BigDecimal(usableAbs), new BigDecimal(provisionalAbs), new BigDecimal(confirmedGlAbs),
                provisionalItemCount, rowCount, 0, 0, 0, 0, 0};
    }

    private static Object[] evidenceRow(int sourceDocumentCount, int sourceItemCount, int valuedItemCount,
            int missingDkkControlCount, int duplicateRiskCount,
            int provisionalNativeCount, int provisionalMonthlyFxCount) {
        return new Object[]{sourceDocumentCount, sourceItemCount, valuedItemCount,
                missingDkkControlCount, duplicateRiskCount, BigDecimal.ZERO, BigDecimal.ZERO,
                provisionalNativeCount, provisionalMonthlyFxCount};
    }

    private static Object[] reconciliationRow() {
        return new Object[]{BigDecimal.ZERO, BigDecimal.ZERO, 0};
    }

    private static Class<?> queryTimeoutBudgetType() {
        try {
            return Class.forName("dk.trustworks.intranet.aggregates.practices.services."
                    + "CxoPracticeContributionService$QueryTimeoutBudget");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Object queryTimeoutBudget() {
        Class<?> type = queryTimeoutBudgetType();
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
                    case "remainingMillis" -> 10_000;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "budget";
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T invokeStatic(String name, Class<?>[] parameterTypes, Object... arguments) {
        return invoke(new CxoPracticeContributionService(), name, parameterTypes, arguments);
    }

    private static LongSupplier sequencedNanos(long... values) {
        List<Long> remaining = new ArrayList<>();
        for (long value : values) remaining.add(value);
        return () -> {
            if (remaining.isEmpty()) throw new AssertionError("Monotonic clock read more often than expected");
            return remaining.removeFirst();
        };
    }

    private static class RecordingReadTransactions extends PracticeContributionReadTransactionRunner {
        private final List<Integer> timeouts = new ArrayList<>();

        @Override
        public <T> T requiringNew(int timeoutSeconds, Supplier<T> work) {
            timeouts.add(timeoutSeconds);
            return work.get();
        }

        List<Integer> timeouts() {
            return List.copyOf(timeouts);
        }
    }

    private static final class QueryTimeoutFailure extends RuntimeException {
    }
}
