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
 * <p>Storage is dual-pathed, both in S3:</p>
 * <ul>
 *   <li><b>Candidate-linked tokens</b> ({@link #candidateUuid} set) →
 *       file lives in the candidate's staging space via
 *       {@code RecruitmentS3StorageService}; {@link #s3FileUuid} holds the
 *       file UUID.</li>
 *   <li><b>User-linked tokens</b> ({@link #userUuid} set) → file lives in
 *       the employee document store; {@link #employeeDocumentUuid} holds
 *       the {@code employee_documents.uuid}.</li>
 * </ul>
 *
 * <p>The {@code candidate_uuid} XOR {@code user_uuid} invariant is mirrored
 * by a CHECK constraint on the table.</p>
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

    @Column(name = "s3_file_uuid", length = 36)
    private String s3FileUuid;

    /**
     * {@code employee_documents.uuid} for user-flow uploads stored in the
     * employee document store (employee-documents spec §6.5.4, V454).
     */
    @Column(name = "employee_document_uuid", length = 36)
    private String employeeDocumentUuid;

    @Column(name = "original_filename", length = 500, nullable = false)
    private String originalFilename;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    /**
     * True when the candidate stored this document through "submit anyway"
     * after the AI gate had already refused it twice (V561). The bytes were
     * never approved by the model, so a human needs to look at them — the HR
     * Slack notification calls the document out by name.
     *
     * <p>False for everything the gate approved, and for every row written
     * before the override existed.</p>
     */
    @Column(name = "manual_review_required", nullable = false)
    private boolean manualReviewRequired;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }

    /** All submissions made for the given token, ordered by upload time. */
    public static List<OnboardingUploadSubmission> findByToken(String tokenUuid) {
        return list("tokenUuid = ?1 ORDER BY uploadedAt", tokenUuid);
    }

    /** Document types on this token that skipped AI approval, in a stable order. */
    public static List<OnboardingDocumentType> manualReviewTypes(List<OnboardingUploadSubmission> submissions) {
        return submissions.stream()
                .filter(OnboardingUploadSubmission::isManualReviewRequired)
                .map(OnboardingUploadSubmission::getDocumentType)
                .sorted()
                .toList();
    }

    /** Whether the {@code (token, type)} pair already has a submission row. */
    public static boolean existsForTokenAndType(String tokenUuid, OnboardingDocumentType type) {
        return count("tokenUuid = ?1 AND documentType = ?2", tokenUuid, type) > 0;
    }

    /**
     * All staging-stored onboarding submissions for the given candidate,
     * ordered by document type so the promotion pass and log output are
     * deterministic. Used by {@code S3EmployeePromotionService} to move
     * identity documents into the employee store at promotion time.
     */
    public static List<OnboardingUploadSubmission> findS3SubmissionsByCandidate(String candidateUuid) {
        return list(
                "candidateUuid = ?1 AND s3FileUuid IS NOT NULL ORDER BY documentType",
                candidateUuid);
    }
}
