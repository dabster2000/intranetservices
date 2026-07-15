package dk.trustworks.intranet.aggregates.practices.dto.cxo;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record PracticePortfolioReconciliationDTO(
        String recognizedNetRevenueDkk,
        String corePracticeRevenueDkk,
        String revenueOnlySegmentDkk,
        String confirmedAttributedRevenueDkk,
        String estimatedRevenueDkk,
        String partialAttributionAffectedRevenueDkk,
        String portfolioUnassignedRevenueDkk,
        String confirmedAttributedCoveragePct,
        String attributedCoveragePct,
        String unassignedCoveragePct,
        int sourceDocumentCount,
        int sourceItemCount,
        int valuedItemCount,
        String valuationItemCoveragePct,
        int duplicateRiskDocumentCount,
        int missingDkkControlCount,
        Map<String, String> missingNativeAmountsByCurrency,
        String reconciliationDifferenceDkk,
        String reconciliationStatus,
        String availabilityReason) {
    public PracticePortfolioReconciliationDTO {
        missingNativeAmountsByCurrency = missingNativeAmountsByCurrency == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(missingNativeAmountsByCurrency));
    }
}
