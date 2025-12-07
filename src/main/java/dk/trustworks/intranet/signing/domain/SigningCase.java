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
     * NextSign case key (_id from API).
     * This is the unique identifier used for all NextSign API calls.
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
     * NextSign internal key (nextSignKey from API).
     * For debugging and advanced queries.
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
    }

    /**
     * JPA lifecycle callback to update updated_at on every update.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
