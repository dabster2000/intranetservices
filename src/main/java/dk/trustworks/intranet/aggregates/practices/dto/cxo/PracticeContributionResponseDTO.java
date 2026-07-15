package dk.trustworks.intranet.aggregates.practices.dto.cxo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record PracticeContributionResponseDTO(
        String scope,
        String revenueBasis,
        String costSource,
        String responseStatus,
        String responseReason,
        String reportingThroughMonth,
        PracticeContributionPeriodDTO currentPeriod,
        PracticeContributionPeriodDTO priorPeriod,
        String revenueGenerationId,
        Instant revenuePublishedAt,
        Instant revenueSourceRefreshedAt,
        String fullBiRefreshVersion,
        Map<String, String> sourceWatermarkVersions,
        Instant pairedCostGenerationAt,
        Instant costPublishedAt,
        String practiceBasisGenerationId,
        String revenueAttributionMethod,
        String costAttributionMethod,
        LocalDate revenueHistoryCoverageStart,
        LocalDate costHistoryCoverageStart,
        boolean practiceBasesAligned,
        String practiceBasesAlignmentReason,
        PracticePortfolioReconciliationDTO currentPortfolio,
        PracticePortfolioReconciliationDTO priorPortfolio,
        List<PracticeContributionPracticeDTO> practices,
        List<PracticeRevenueSegmentDTO> revenueOnlySegments) {
    public PracticeContributionResponseDTO {
        sourceWatermarkVersions = sourceWatermarkVersions == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sourceWatermarkVersions));
        practices = practices == null ? List.of() : List.copyOf(practices);
        revenueOnlySegments = revenueOnlySegments == null
                ? List.of() : List.copyOf(revenueOnlySegments);
    }
}
