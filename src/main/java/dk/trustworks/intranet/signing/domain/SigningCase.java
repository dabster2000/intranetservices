package dk.trustworks.intranet.signing.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Set;

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
     * Signing statuses from which NextSign can make no further progress AND
     * no signed document can ever be produced. A case observed in one of
     * these is marked {@code processing_status = SKIPPED} and permanently
     * leaves the status-sync poll set.
     */
    public static final Set<String> TERMINAL_NON_UPLOADABLE_STATUSES = Set.of(
        "expired", "rejected", "denied", "cancelled"
    );

    /**
     * All statuses where signing can make no further progress — the
     * non-uploadable set above plus {@code completed}. The status-sync
     * batchlet keeps polling a case until it reaches one of these; the
     * poll-set query in {@code SigningCaseRepository#findCasesNeedingStatusFetch}
     * and the skip logic in {@code SigningService} must agree on this set,
     * which is why it lives on the domain class. Values are lowercase — the
     * query compares against {@code lower(status)}.
     */
    public static final Set<String> TERMINAL_STATUSES = Set.of(
        "completed", "expired", "rejected", "denied", "cancelled"
    );

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
     * Values: PENDING_FETCH, FETCHING, COMPLETED, FAILED, SKIPPED.
     *
     * Handles race condition where NextSign needs time before cases are queryable.
     * Cases start as PENDING_FETCH and are processed by background batch job.
     * Terminal non-uploadable cases are marked SKIPPED and are not selected again.
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

    /**
     * Case title from NextSign (may differ from documentName).
     */
    @Column(name = "title", length = 500)
    private String title;

    /**
     * Number of days the case is available for signing.
     * Null if not yet fetched from NextSign.
     */
    @Column(name = "availability_days")
    private Integer availabilityDays;

    /**
     * Whether the case has unlimited availability (no expiry).
     */
    @Column(name = "availability_unlimited", nullable = false)
    @Builder.Default
    private Boolean availabilityUnlimited = false;

    // --- S3 Archival Fields (employee-documents spec §6.5.1, V454) ---

    /**
     * S3 archival state of the signed documents: PENDING (not yet archived / retrying — some documents may
     * already have rows, idempotent per {@code uq_ed_signing}), ARCHIVED
     * (every document of the case has an {@code employee_documents} row /
     * recruitment cases: a {@code signed_pdfs_snapshot}), SKIPPED
     * (terminal non-uploadable case, mirrors {@code processing_status}).
     */
    @Column(name = "archive_status", length = 20)
    @Builder.Default
    private String archiveStatus = "PENDING";

    /** Last S3 archival error; cleared on success. */
    @Column(name = "archive_error", columnDefinition = "TEXT")
    private String archiveError;

    /**
     * Failed S3 archival attempts (V551). The catch-up sweep selects on
     * {@code archive_status='PENDING'} alone, and a failure leaves the row
     * PENDING, so without this counter a case whose NextSign envelope has
     * expired would be retried every 5-minute pass forever. At
     * {@code EmployeeSigningArchivalService.MAX_ARCHIVE_ATTEMPTS} the case
     * goes terminal as SKIPPED with its last {@code archive_error} kept.
     *
     * <p>Distinct from {@link #retryCount}, which counts NextSign
     * <em>status-fetch</em> failures and is never read by archival.</p>
     */
    @Column(name = "archive_attempts")
    @Builder.Default
    private Integer archiveAttempts = 0;

    /**
     * {@code document_templates.uuid} the case was created from (null for
     * template-less cases). Set at creation time; drives the archival
     * category mapping (TemplateCategory → EmployeeDocumentCategory).
     */
    @Column(name = "template_uuid", length = 36)
    private String templateUuid;

    /**
     * Sender-chosen archival category ({@code EmployeeDocumentCategory}
     * enum name, V475) for template-less cases created via the upload
     * wizard. At S3 archival time an explicit value here wins over the
     * {@link #templateUuid} mapping; NULL falls back to the template
     * mapping (else OTHER).
     */
    @Column(name = "archive_category", length = 20)
    private String archiveCategory;

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
        if (archiveStatus == null) {
            archiveStatus = "PENDING";
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
