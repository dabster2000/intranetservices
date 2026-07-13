package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.time.LocalDate;

public record IndividualBonusAdjustmentRequest(
        long version,
        LocalDate payMonth,
        Boolean openPayrollAttestation
) {
}
