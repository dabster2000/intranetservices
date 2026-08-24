package dk.trustworks.intranet.vacationservice.dto;

public record VacationWarningDTO(
        String type,
        int ferieaar,
        String label,
        double days,
        String message) {
}
