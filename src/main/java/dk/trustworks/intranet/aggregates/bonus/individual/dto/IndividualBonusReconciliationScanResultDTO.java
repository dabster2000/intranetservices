package dk.trustworks.intranet.aggregates.bonus.individual.dto;

public record IndividualBonusReconciliationScanResultDTO(
        int scanned,
        int noChange,
        int created,
        int superseded,
        int blocked,
        int resolved
) {
}
