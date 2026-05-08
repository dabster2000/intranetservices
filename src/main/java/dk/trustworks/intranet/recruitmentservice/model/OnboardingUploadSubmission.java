package dk.trustworks.intranet.recruitmentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Audit row for a single identity-document upload made through the public
 * onboarding upload page. One row per {@code (token, document_type)} pair —
 * the {@code uk_ous_token_doctype} unique key enforces "one upload per type
 * per token" at the DB level so a leaked link cannot flood storage with
 * duplicates.
 *
 * <p>Storage is dual-pathed:</p>
 * <ul>
 *   <li><b>Candidate-linked tokens</b> ({@link #candidateUuid} set) →
 *       file lives in S3 via {@code S3FileService}; {@link #s3FileUuid}
 *       holds the file UUID.</li>
 *   <li><b>User-linked tokens</b> ({@link #userUuid} set) → file lives in
 *       SharePoint under {@code {EMPLOYEE folder}/{username}/Onboarding/};
 *       {@link #sharepointDriveItemId} and {@link #sharepointWebUrl} hold
 *       the Microsoft Graph identifiers.</li>
 * </ul>
 *
 * <p>The {@code candidate_uuid} XOR {@code user_uuid} invariant is mirrored
 * by a CHECK constraint on the table, and so is the storage_target ↔
 * identifier consistency.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "onboarding_upload_submissions")
public class OnboardingUploadSubmission extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    @Column(name = "token_uuid", length = 36, nullable = false)
    private String tokenUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private OnboardingDocumentType documentType;

    /** Soft-FK to {@code recruitment_candidates.uuid}. Mutually exclusive with {@link #userUuid}. */
    @Column(name = "candidate_uuid", length = 36)
    private String candidateUuid;

    /** Soft-FK to {@code users.uuid}. Mutually exclusive with {@link #candidateUuid}. */
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_target", nullable = false)
    private StorageTarget storageTarget;

    @Column(name = "s3_file_uuid", length = 36)
    private String s3FileUuid;

    @Column(name = "sharepoint_drive_item_id", length = 255)
    private String sharepointDriveItemId;

    @Column(name = "sharepoint_web_url", length = 1024)
    private String sharepointWebUrl;

    @Column(name = "original_filename", length = 500, nullable = false)
    private String originalFilename;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    /**
     * Lifecycle timestamp for the recruitment S3 reaper. NULL means
     * <em>not eligible for reaping</em> (default — set on every row at
     * insert time). Stamped to {@code NOW + 30 days} when the candidate's
     * promotion-time SharePoint copy reaches
     * {@link dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus#COMPLETED}
     * and cleared back to NULL once the S3 reaper has deleted the
     * underlying object. Has no meaning for SHAREPOINT-target rows, which
     * never own an S3 object to reap.
     */
    @Column(name = "s3_retention_until")
    private LocalDateTime s3RetentionUntil;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }

    public enum StorageTarget {
        S3,
        SHAREPOINT
    }

    /** All submissions made for the given token, ordered by upload time. */
    public static List<OnboardingUploadSubmission> findByToken(String tokenUuid) {
        return list("tokenUuid = ?1 ORDER BY uploadedAt", tokenUuid);
    }

    /** Whether the {@code (token, type)} pair already has a submission row. */
    public static boolean existsForTokenAndType(String tokenUuid, OnboardingDocumentType type) {
        return count("tokenUuid = ?1 AND documentType = ?2", tokenUuid, type) > 0;
    }
}
