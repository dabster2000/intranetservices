package dk.trustworks.intranet.utils.dto.signing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Complete status information for a signing case.
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
 */
public record SigningCaseStatus(
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
    String sharepointFileUrl
) {
    /**
     * Checks if all signers have completed signing.
     */
    public boolean isComplete() {
        return "completed".equalsIgnoreCase(status) || completedSigners >= totalSigners;
    }

    /**
     * Returns the signing progress as a percentage (0-100).
     */
    public int progressPercent() {
        if (totalSigners == 0) return 0;
        return (completedSigners * 100) / totalSigners;
    }
}
