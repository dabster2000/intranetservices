package dk.trustworks.intranet.vacationservice.dto;

/** One HR follow-up item on the admin console. */
public record VacationFlagDTO(
        String useruuid,
        String fullname,
        String type,
        String pool,
        int ferieaar,
        String label,
        double days,
        String message) {
}
