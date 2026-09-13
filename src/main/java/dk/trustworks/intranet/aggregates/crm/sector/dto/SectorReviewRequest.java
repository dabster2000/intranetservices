package dk.trustworks.intranet.aggregates.crm.sector.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Closing a sector review. As on the account plan, the facts come from the caller: they
 * are what the PAGE was showing while the meeting happened, not what a recomputation
 * afterwards would say.
 */
public record SectorReviewRequest(
        LocalDate date,
        List<String> attendeeUuids,
        String outcome,
        List<String> decisions,
        LocalDate nextReview,
        Map<String, String> objectiveRags,
        Double weightedRate,
        int consultants,
        int openLeads,
        double weightedPipeline,
        double fyRevenue,
        Map<String, Integer> accountsByBand,
        Map<String, Integer> planCoverage) {
}
