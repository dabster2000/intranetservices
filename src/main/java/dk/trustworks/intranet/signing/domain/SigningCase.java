package dk.trustworks.intranet.signing.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * JPA Entity representing a document signing case tracked in our system.
 * Stores minimal metadata for cases created via NextSign API.
 *
 * Full case details are fetched on-demand from NextSign using the caseKey.
 * This table enables:
 * - Persistent tracking across sessions
 * - User-scoped filtering
 * - Fast list queries without external API calls
 * - Sync detection for externally-created cases
 *
 * Related entities:
 * - User (via user_uuid foreign key)
 * - NextSign external system (via case_key)
 */
@Entity
@Table(name = "signing_cases")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SigningCase {

    /**
     * Internal database ID.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * NextSign case id (MongoDB _id from API).
     * This is the unique identifier used for all NextSign API calls (e.g., getCaseStatus).
     * Format: 24-character hex string like "693729174b7454ef1809e086".
     */
    @Column(name = "case_key", nullable = false, unique = true, length = 255)
    private String caseKey;

    /**
     * User UUID who created/owns this signing case.
     * References users.uuid.
     */
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    /**
     * Document name/title for display purposes.
     */
    @Column(name = "document_name", nullable = false, length = 500)
    private String documentName;

    /**
     * Current case status: PENDING, IN_PROGRESS, COMPLETED, EXPIRED, etc.
     * Matches NextSign case_status field.
     */
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    /**
     * Async processing status for batch job tracking.
     * Values: PENDING_FETCH, FETCHING, COMPLETED, FAILED.
     *
     * Handles race condition where NextSign needs time before cases are queryable.
     * Cases start as PENDING_FETCH and are processed by background batch job.
     */
    @Column(name = "processing_status", length = 50)
    @Builder.Default
    private String processingStatus = "PENDING_FETCH";

    /**
     * Timestamp when status was last fetched from NextSign.
     * Used for retry logic and monitoring.
     */
    @Column(name = "last_status_fetch")
    private LocalDateTime lastStatusFetch;

    /**
     * Last error message if status fetch failed.
     * Helps diagnose NextSign API issues.
     */
    @Column(name = "status_fetch_error", columnDefinition = "TEXT")
    private String statusFetchError;

    /**
     * Number of failed fetch attempts.
     * Incremented on each failure, reset on success.
     * Used to prevent infinite retries.
     */
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * Optional internal reference ID.
     * Often set to user UUID for tracking.
     */
    @Column(name = "reference_id", length = 255)
    private String referenceId;

    /**
     * Case creation timestamp.
     * Set from NextSign created_at or on insert.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Last status update timestamp.
     * Updated when case status changes.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * NextSign human-readable key (nextSignKey from API).
     * Format: "xxxx-xxxx-xxxx-xxxx-xxxxx" style, e.g., "8af3x-KMRpr-wJs9f-G29KI-jKAR0".
     * Used for display purposes and signing links; NOT for API calls.
     */
    @Column(name = "nextsign_key", length = 255)
    private String nextsignKey;

    /**
     * Total number of signers in this case.
     */
    @Column(name = "total_signers")
    private Integer totalSigners;

    /**
     * Number of signers who have completed signing.
     */
    @Column(name = "completed_signers")
    private Integer completedSigners;

    /**
     * NextSign folder/category.
     */
    @Column(name = "folder", length = 255)
    private String folder;

    // --- SharePoint Auto-Upload Fields ---

    /**
     * Reference to template_signing_stores.uuid for auto-upload configuration.
     * If set, signed documents will be automatically uploaded to SharePoint.
     */
    @Column(name = "signing_store_uuid", length = 36)
    private String signingStoreUuid;

    /**
     * SharePoint upload status: PENDING, UPLOADED, FAILED.
     * Tracks whether the signed document has been uploaded to SharePoint.
     */
    @Column(name = "sharepoint_upload_status", length = 50)
    private String sharepointUploadStatus;

    /**
     * Error message if SharePoint upload failed.
     */
    @Column(name = "sharepoint_upload_error", columnDefinition = "TEXT")
    private String sharepointUploadError;

    /**
     * URL of the uploaded file in SharePoint.
     * Set after successful upload for easy access.
     */
    @Column(name = "sharepoint_file_url", length = 1000)
    private String sharepointFileUrl;

    /**
     * JPA lifecycle callback to set created_at on first persist.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (totalSigners == null) {
            totalSigners = 0;
        }
        if (completedSigners == null) {
            completedSigners = 0;
        }
        if (folder == null) {
            folder = "Default";
        }
        if (processingStatus == null) {
            processingStatus = "PENDING_FETCH";
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }

    /**
     * JPA lifecycle callback to update updated_at on every update.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
