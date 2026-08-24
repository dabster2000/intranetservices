package dk.trustworks.intranet.vacationservice.dto;

/**
 * One statutory/contractual warning. {@code message} is the Danish sentence
 * used by the HR console; {@code type}, {@code pool}, {@code label} and
 * {@code days} carry the same facts structurally so a client can render its
 * own wording.
 */
public record VacationWarningDTO(
        String type,
        String pool,
        int ferieaar,
        String label,
        double days,
        String message) {
}
