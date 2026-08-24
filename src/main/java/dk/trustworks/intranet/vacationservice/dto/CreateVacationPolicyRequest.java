package dk.trustworks.intranet.vacationservice.dto;

import java.time.LocalDate;

public record CreateVacationPolicyRequest(
        LocalDate effectiveFrom,
        double ferieDaysPerMonth,
        double feriefridageDaysPerMonth) {
}
