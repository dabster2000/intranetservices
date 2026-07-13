package dk.trustworks.intranet.aggregates.bonus.individual.dto;

public record IndividualBonusAdjustmentConfirmDTO(
        IndividualBonusAdjustmentDTO adjustment,
        String lumpSumUuid,
        boolean idempotent
) {
}
