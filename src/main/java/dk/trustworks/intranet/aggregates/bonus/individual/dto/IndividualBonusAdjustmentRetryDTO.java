package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndividualBonusAdjustmentRetryDTO(
        IndividualBonusAdjustmentDTO adjustment,
        String outcome,
        ProjectedPayoutDTO payout,
        IndividualBonusAdjustmentDTO successorAdjustment
) {
}
