package dk.trustworks.intranet.vacationservice.dto;

import java.time.LocalDate;
import java.util.List;

public record VacationOverviewDTO(
        String useruuid,
        LocalDate baselineAsOf,
        List<VacationPoolDTO> pools,
        List<VacationWarningDTO> warnings) {
}
