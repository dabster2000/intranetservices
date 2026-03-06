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
 * @param signingStoreUuid Reference to signing store for SharePoint auto-upload (nullable)
 * @param sharepointUploadStatus SharePoint upload status: PENDING, UPLOADED, FAILED (nullable)
 * @param sharepointUploadError Error message if SharePoint upload failed (nullable)
 * @param sharepointFileUrl URL of the uploaded file in SharePoint (nullable)
 * @param userUuid UUID of the user who owns this signing case
 * @param employeeName Resolved full name of the employee (nullable)
 * @param templateName Resolved template name via signing store (nullable)
 * @param processingStatus Async processing status: PENDING_FETCH, FETCHING, COMPLETED, FAILED
 * @param retryCount Number of failed fetch attempts
 * @param lastStatusFetch Timestamp when status was last fetched from NextSign
 */
public record AdminSigningCaseDTO(
    String caseKey,
    String status,
    String documentName,
    LocalDateTime createdAt,
    List<SignerStatus> signers,
    int totalSigners,
    int completedSigners,
    String signingStoreUuid,
    String sharepointUploadStatus,
    String sharepointUploadError,
    String sharepointFileUrl,
    // Admin-specific fields:
    String userUuid,
    String employeeName,
    String templateName,
    String processingStatus,
    Integer retryCount,
    LocalDateTime lastStatusFetch
) {}
