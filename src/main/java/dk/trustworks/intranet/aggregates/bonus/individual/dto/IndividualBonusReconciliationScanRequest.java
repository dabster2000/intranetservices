package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.time.LocalDate;

public record IndividualBonusReconciliationScanRequest(
        String userUuid,
        LocalDate earningMonthFrom,
        LocalDate earningMonthTo,
        String ruleUuid,
        Boolean dryRun
) {
}
