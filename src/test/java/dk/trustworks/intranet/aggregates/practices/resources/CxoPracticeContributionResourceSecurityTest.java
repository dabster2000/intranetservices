package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionEvidenceDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPeriodDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionPracticeDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticePortfolioReconciliationDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeRevenueSegmentDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CxoPracticeContributionResourceSecurityTest {

    @Test
    void frameworkBoundaryRequiresAuthenticationAndOnlyDashboardReadScope() throws Exception {
        Class<CxoPracticeContributionResource> resource = CxoPracticeContributionResource.class;
        assertEquals("/practices/cxo/contribution", resource.getAnnotation(Path.class).value());
        assertArrayEquals(new String[]{"dashboard:read"}, resource.getAnnotation(RolesAllowed.class).value());
        assertNotNull(resource.getAnnotation(RequestScoped.class));
        assertEquals("jwt", resource.getAnnotation(SecurityRequirement.class).name());
        assertNotNull(resource.getDeclaredMethod("getContribution", jakarta.ws.rs.core.UriInfo.class)
                .getAnnotation(GET.class));
    }

    @Test
    void aggregateContractHasExactTopLevelAndNestedPropertyAllowLists() {
        assertEquals(Set.of(
                        "scope", "revenueBasis", "costSource", "responseStatus", "responseReason",
                        "reportingThroughMonth", "currentPeriod", "priorPeriod", "revenueGenerationId",
                        "revenuePublishedAt", "revenueSourceRefreshedAt", "fullBiRefreshVersion",
                        "sourceWatermarkVersions", "pairedCostGenerationAt", "costPublishedAt",
                        "practiceBasisGenerationId", "revenueAttributionMethod", "costAttributionMethod",
                        "revenueHistoryCoverageStart", "costHistoryCoverageStart", "practiceBasesAligned",
                        "practiceBasesAlignmentReason", "currentPortfolio", "priorPortfolio", "practices",
                        "revenueOnlySegments"),
                components(PracticeContributionResponseDTO.class));
        assertEquals(Set.of(
                        "startMonth", "endMonth", "sourceStatus", "sourceReason", "fxStatus", "fxReason",
                        "attributionStatus", "attributionReason", "revenueAttributionMethod",
                        "revenueRegisteredWorkValueAllocationCount", "revenueRegisteredHoursAllocationCount",
                        "revenueScheduledCapacityFallbackAllocationCount",
                        "revenueMonthEndPracticeFallbackAllocationCount",
                        "costMonthEndPracticeFallbackEmployeeMonthCount",
                        "historicalPracticeFallbackAllocationCount", "historicalPracticeFallbackUsed",
                        "attributionExplanationCode", "attributionExplanation", "costCompletenessStatus",
                        "costCompletenessReason", "fteCompletenessStatus", "fteCompletenessReason",
                        "expectedFteCellCount", "coveredFteCellCount", "missingFteCellCount",
                        "practiceBasisStatus", "practiceBasisReason", "contributionStatus",
                        "availabilityReason", "evidence"),
                components(PracticeContributionPeriodDTO.class));
        assertEquals(Set.of(
                        "practiceId", "label", "current", "prior", "revenueDeltaDkk", "revenueDeltaPct",
                        "costDeltaDkk", "costDeltaPct", "contributionDeltaDkk", "contributionDeltaPct",
                        "contributionMarginDeltaPoints"),
                components(PracticeContributionPracticeDTO.class));
        assertEquals(Set.of(
                        "segmentId", "label", "current", "prior", "revenueDeltaDkk", "revenueDeltaPct"),
                components(PracticeRevenueSegmentDTO.class));
    }

    @Test
    void noPublicAggregateCarrierCanExposeConsultantInvoiceOrRawSalaryIdentity() {
        List<Class<?>> carriers = List.of(
                PracticeContributionResponseDTO.class,
                PracticeContributionPeriodDTO.class,
                PracticeContributionEvidenceDTO.class,
                PracticeContributionPracticeDTO.class,
                PracticeContributionPracticeDTO.Metrics.class,
                PracticeRevenueSegmentDTO.class,
                PracticeRevenueSegmentDTO.Metrics.class,
                PracticePortfolioReconciliationDTO.class);

        for (Class<?> carrier : carriers) {
            for (String name : components(carrier)) {
                String normalized = name.toLowerCase();
                assertFalse(normalized.contains("consultant"), carrier + " exposes " + name);
                assertFalse(normalized.contains("useruuid"), carrier + " exposes " + name);
                assertFalse(normalized.contains("invoiceuuid"), carrier + " exposes " + name);
                assertFalse(normalized.contains("description"), carrier + " exposes " + name);
                assertFalse(normalized.contains("salarydetail"), carrier + " exposes " + name);
            }
        }
    }

    @Test
    void safe503EnvelopeContainsOnlyClosedCode() {
        ContributionUnavailableExceptionMapper.ContributionUnavailableResponse body =
                new ContributionUnavailableExceptionMapper.ContributionUnavailableResponse(
                        "PRACTICE_CONTRIBUTION_PUBLICATION_UNAVAILABLE");

        assertEquals(Set.of("code"), components(body.getClass()));
        assertNull(body.getClass().getEnclosingClass().getAnnotation(Path.class));
    }

    private static Set<String> components(Class<?> type) {
        assertTrue(type.isRecord(), () -> type + " must remain an immutable record contract");
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
