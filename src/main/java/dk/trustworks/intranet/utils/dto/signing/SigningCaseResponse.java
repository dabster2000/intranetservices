package dk.trustworks.intranet.utils.dto.signing;

import java.time.LocalDateTime;

/**
 * Response after creating a signing case.
 *
 * @param caseKey NextSign case key for tracking the signing workflow
 * @param status Current status of the case ("created", "pending", "completed", etc.)
 * @param documentName Name of the document being signed
 * @param createdAt Timestamp when the case was created
 */
public record SigningCaseResponse(
    String caseKey,
    String status,
    String documentName,
    LocalDateTime createdAt
) {
    /**
     * Creates a response for a newly created case.
     */
    public static SigningCaseResponse created(String caseKey, String documentName) {
        return new SigningCaseResponse(caseKey, "created", documentName, LocalDateTime.now());
    }
}
