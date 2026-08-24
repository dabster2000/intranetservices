package dk.trustworks.intranet.vacationservice.dto;

import java.time.LocalDate;

public record VacationProjectionPointDTO(
        LocalDate date,
        double ferieRemaining,
        double feriefridageRemaining,
        double totalRemaining) {
}
