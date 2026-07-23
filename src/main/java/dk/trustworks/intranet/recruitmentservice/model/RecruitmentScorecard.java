package dk.trustworks.intranet.recruitmentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.recruitmentservice.model.enums.ScorecardRecommendation;
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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * One interviewer's blind scorecard for one interview (ATS spec §4.1/§5.3).
 * Scores key on the position's scorecard-template attribute codes (P2
 * snapshot); free-text notes are deliberately NOT a column — they live in
 * the {@code SCORECARD_SUBMITTED} event's {@code pii} block (spec §4.1:
 * free-text personal content only in event pii, so anonymization has
 * exactly four targets).
 * <p>
 * The blind rule (others' scorecards hidden until your own is submitted;
 * owner/recruiter unlocked when all are in or after the decision) is
 * enforced server-side in {@code RecruitmentInterviewService} — never
 * client-side. One scorecard per interviewer per interview (DB UNIQUE).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "recruitment_scorecards")
@EntityListeners(AuditEntityListener.class)
public class RecruitmentScorecard extends PanacheEntityBase implements Auditable {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** FK to {@code recruitment_interviews.uuid}. */
    @Column(name = "interview_uuid", length = 36, nullable = false, updatable = false)
    private String interviewUuid;

    /** Soft FK to {@code users.uuid} — the submitting interviewer. */
    @Column(name = "interviewer_uuid", length = 36, nullable = false, updatable = false)
    private String interviewerUuid;

    /** Attribute code → 1..4, keyed by the position's template codes. */
    @Convert(converter = ScoreMapConverter.class)
    @Column(name = "scores", columnDefinition = "JSON", nullable = false)
    private Map<String, Integer> scores;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 10, nullable = false)
    private ScorecardRecommendation recommendation;

    /** UTC. The blind rule pivots on submission. */
    @Column(name = "submitted_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime submittedAt;

    /** P17 SLA-nudge bookkeeping (max 2 nudges); data-only in P11. */
    @Column(name = "overdue_nudged", nullable = false)
    private boolean overdueNudged;

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
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
