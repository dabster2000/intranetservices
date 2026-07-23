package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * One interview on one application (ATS spec §4.1/§5.3): rounds 1–3 count
 * toward the stage machine (round <em>n</em> ↔ stage {@code INTERVIEW_n});
 * {@code INFORMAL} is the schedulable <em>uformel snak</em> that never
 * advances the stage and takes no scorecard.
 * <p>
 * State changes are only made through {@code RecruitmentInterviewService},
 * which pairs every mutation with its {@code INTERVIEW_*} event. Interviewer
 * assignment ({@link #interviewerUuids}) grants per-candidate involvement in
 * {@code RecruitmentVisibility} (spec §7.2 "Interviewer = per-candidate
 * assignment, not a standing role").
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_interviews")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentInterview extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_applications.uuid}. */
    @Column(name = "application_uuid", length = 36, nullable = false, updatable = false)
    private String applicationUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10, nullable = false, updatable = false)
    private RecruitmentInterviewKind kind;

    /** 1..3 for {@code ROUND}; {@code NULL} for {@code INFORMAL}. */
    @Column(name = "round", updatable = false)
    private Integer round;

    /** UTC. */
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    /**
     * Outlook linkage when Graph calendar scheduling is enabled
     * ({@code dk.trustworks.recruitment.graph.calendar.enabled});
     * {@code NULL} under manual scheduling (plan §P11 fallback).
     */
    @Column(name = "graph_event_id", length = 255)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String graphEventId;

    /** Soft FKs to {@code users.uuid} — the assigned interviewers. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "interviewer_uuids", columnDefinition = "JSON", nullable = false)
    private List<String> interviewerUuids;

    /** PII-free: room name or {@code "Teams"} (spec §4.1). */
    @Column(name = "location", length = 200)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private RecruitmentInterviewStatus status = RecruitmentInterviewStatus.SCHEDULED;

    // ---- Audit columns (house Auditable pattern) ---------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = RecruitmentInterviewStatus.SCHEDULED;
        }
    }

    /** @return true iff this interview may still be acted on. */
    public boolean isActive() {
        return status != RecruitmentInterviewStatus.CANCELLED;
    }

    /**
     * The pipeline stage this interview belongs to — {@code INTERVIEW_n}
     * for round <em>n</em>, {@code null} for {@code INFORMAL} (which never
     * maps to a stage). The blind rule's "after decision" unlock compares
     * the application's current stage against this.
     */
    public RecruitmentStage roundStage() {
        if (kind != RecruitmentInterviewKind.ROUND || round == null) {
            return null;
        }
        return switch (round) {
            case 1 -> RecruitmentStage.INTERVIEW_1;
            case 2 -> RecruitmentStage.INTERVIEW_2;
            case 3 -> RecruitmentStage.INTERVIEW_3;
            default -> null;
        };
    }

    /** Whether the given user is one of the assigned interviewers. */
    public boolean isAssigned(String userUuid) {
        return userUuid != null && interviewerUuids != null && interviewerUuids.contains(userUuid);
    }
}
