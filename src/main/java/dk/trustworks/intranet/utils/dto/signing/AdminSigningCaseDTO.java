package dk.trustworks.intranet.utils.dto.signing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-specific signing case DTO with enriched data.
 * Extends the fields from SigningCaseStatus with admin-specific fields
 * such as employee names, template names, and processing metadata.
 *
 * Used by the admin view to display a global overview of all signing cases.
 *
 * @param caseKey NextSign case key
 * @param status Overall case status ("pending", "completed", "rejected", "expired", "cancelled")
 * @param documentName Name of the document being signed
 * @param createdAt Timestamp when the case was created
 * @param signers Status of each individual signer
 * @param totalSigners Total number of signers
 * @param completedSigners Number of signers who have completed signing
 * @param userUuid UUID of the user who owns this signing case
 * @param employeeName Resolved full name of the employee (nullable)
 * @param templateName Resolved template name (nullable)
 * @param processingStatus Async processing status: PENDING_FETCH, FETCHING, COMPLETED, FAILED, SKIPPED
 * @param retryCount Number of failed fetch attempts
 * @param lastStatusFetch Timestamp when status was last fetched from NextSign
 * @param title Case title from NextSign (may differ from documentName)
 * @param folder NextSign folder/category
 * @param availabilityDays Number of days the case is available for signing (nullable)
 * @param availabilityUnlimited Whether the case has unlimited availability (no expiry)
 */
public record AdminSigningCaseDTO(
    String caseKey,
    String status,
    String documentName,
    LocalDateTime createdAt,
    List<SignerStatus> signers,
    int totalSigners,
    int completedSigners,
    // Admin-specific fields:
    String userUuid,
    String employeeName,
    String templateName,
    String processingStatus,
    Integer retryCount,
    LocalDateTime lastStatusFetch,
    // New fields for enhanced admin view:
    String title,
    String folder,
    Integer availabilityDays,
    Boolean availabilityUnlimited
) {}
