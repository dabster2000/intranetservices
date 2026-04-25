package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_application")
public class Application extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "candidate_uuid", length = 36, nullable = false)
    public String candidateUuid;

    @Column(name = "role_uuid", length = 36, nullable = false)
    public String roleUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_type", length = 30, nullable = false)
    public ApplicationType applicationType;

    @Column(name = "referrer_user_uuid", length = 36)
    public String referrerUserUuid;

    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    public ApplicationStage stage;

    @Column(name = "is_active", insertable = false, updatable = false)
    public Boolean isActiveGenerated;

    @Column(name = "accepted_for_unique", insertable = false, updatable = false, length = 36)
    public String acceptedForUnique;

    @Column(name = "screening_recommendation", length = 40)
    public String screeningRecommendation;

    @Column(name = "screening_outcome", length = 40)
    public String screeningOutcome;

    @Column(name = "screening_override_reason", columnDefinition = "TEXT")
    public String screeningOverrideReason;

    @Column(name = "screening_decided_by_uuid", length = 36)
    public String screeningDecidedByUuid;

    @Column(name = "screening_decided_at")
    public LocalDateTime screeningDecidedAt;

    @Column(name = "closed_reason", length = 60)
    public String closedReason;

    @Column(name = "interview_booked_at")
    public LocalDateTime interviewBookedAt;

    @Column(name = "last_stage_change_at")
    public LocalDateTime lastStageChangeAt;

    @Column(name = "sla_deadline_at")
    public LocalDateTime slaDeadlineAt;

    @Column(name = "accepted_at")
    public LocalDateTime acceptedAt;

    @Column(name = "converted_at")
    public LocalDateTime convertedAt;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    public Application() {}

    public static Application withFreshUuid() {
        Application a = new Application();
        a.uuid = UUID.randomUUID().toString();
        return a;
    }
}
