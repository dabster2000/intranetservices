package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoundUpDecision;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "recruitment_interview")
@Data
@NoArgsConstructor
public class Interview extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    public String uuid;

    @Column(name = "application_uuid", length = 36, nullable = false)
    public String applicationUuid;

    @Column(name = "round_number", nullable = false)
    public Integer roundNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "round_type", length = 20, nullable = false)
    public InterviewRoundType roundType;

    @Column(name = "scheduled_at", nullable = false)
    public LocalDateTime scheduledAt;

    @Column(name = "duration_minutes", nullable = false)
    public Integer durationMinutes;

    @Column(name = "outlook_event_id", length = 255)
    public String outlookEventId;

    @Column(name = "interview_kit_artifact_uuid", length = 36)
    public String interviewKitArtifactUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 40, nullable = false)
    public InterviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "round_up_decision", length = 40)
    public RoundUpDecision roundUpDecision;

    @Column(name = "round_up_at")
    public LocalDateTime roundUpAt;

    @Column(name = "round_up_summary", columnDefinition = "TEXT")
    public String roundUpSummary;

    // NOTE: V307 schema does NOT include round_up_by_uuid — actor is captured
    // via the audit / activity feed (RoleHistory + activity stream), not on the
    // interview row. Keeping the entity strictly aligned with the migration.

    @Column(name = "reschedule_count", nullable = false)
    public Integer rescheduleCount = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    public LocalDateTime updatedAt;
}
