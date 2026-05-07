package dk.trustworks.intranet.recruitmentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "onboarding_upload_tokens")
public class OnboardingUploadToken extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Soft-FK to recruitment_candidates.uuid — mutually exclusive with userUuid. */
    @Column(name = "candidate_uuid", length = 36)
    private String candidateUuid;

    /** Soft-FK to users.uuid — mutually exclusive with candidateUuid. */
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    @Column(name = "show_drivers_license", nullable = false)
    private boolean showDriversLicense = true;

    @Column(name = "show_health_insurance", nullable = false)
    private boolean showHealthInsurance = true;

    @Column(name = "show_criminal_record", nullable = false)
    private boolean showCriminalRecord = true;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_by_useruuid", length = 36, nullable = false)
    private String createdByUseruuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static Optional<OnboardingUploadToken> findByCandidate(String candidateUuid) {
        return find("candidateUuid", candidateUuid).firstResultOptional();
    }

    public static Optional<OnboardingUploadToken> findByUser(String userUuid) {
        return find("userUuid", userUuid).firstResultOptional();
    }
}
