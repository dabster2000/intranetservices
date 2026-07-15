package dk.trustworks.intranet.aggregates.practices.dto.cxo;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record PracticeContributionEvidenceDTO(
        int sourceDocumentCount,
        int sourceItemCount,
        int valuedItemCount,
        String valuationItemCoveragePct,
        int missingDkkControlCount,
        Map<String, String> missingNativeAmountsByCurrency,
        String confirmedAttributedRevenueDkk,
        String estimatedRevenueDkk,
        String partialAttributionAffectedRevenueDkk,
        String unassignedRevenueDkk,
        String confirmedAttributedCoveragePct,
        String attributedCoveragePct,
        String unassignedCoveragePct,
        int duplicateRiskDocumentCount,
        String documentGlControlDkk,
        String allocatedRevenueDkk,
        String reconciliationDifferenceDkk,
        String reconciliationStatus) {
    public PracticeContributionEvidenceDTO {
        missingNativeAmountsByCurrency = missingNativeAmountsByCurrency == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(missingNativeAmountsByCurrency));
    }
}
