package dk.trustworks.intranet.hrletters.dto;

/** HR's dismiss command — the reason is shown to the employee for vacation requests. */
public record DismissHrLetterRequest(String reason) {
}
