package dk.trustworks.intranet.aggregates.bonus.individual.dto;

import java.time.LocalDate;

public record IndividualBonusRunMonthlyRequest(
        LocalDate payMonth,
        Boolean openPayrollAttestation,
        Boolean dryRun
) {
    public boolean isDryRun() { return Boolean.TRUE.equals(dryRun); }
}
