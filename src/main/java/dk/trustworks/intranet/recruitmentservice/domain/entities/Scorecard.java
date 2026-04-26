package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "recruitment_scorecard",
       uniqueConstraints = @UniqueConstraint(columnNames = {"interview_uuid", "interviewer_user_uuid"}))
@Data
@NoArgsConstructor
public class Scorecard extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    public String uuid;

    @Column(name = "interview_uuid", length = 36, nullable = false)
    public String interviewUuid;

    @Column(name = "interviewer_user_uuid", length = 36, nullable = false)
    public String interviewerUserUuid;

    @Column(name = "practice_skill_fit")
    public Byte practiceSkillFit;

    @Column(name = "career_level_fit")
    public Byte careerLevelFit;

    @Column(name = "consulting_communication")
    public Byte consultingCommunication;

    @Column(name = "client_facing_maturity")
    public Byte clientFacingMaturity;

    @Column(name = "culture_value_fit")
    public Byte cultureValueFit;

    @Column(name = "delivery_track_potential")
    public Byte deliveryTrackPotential;

    @Column(name = "concerns", columnDefinition = "TEXT")
    public String concerns;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 20, nullable = false)
    public ScorecardRecommendation recommendation;

    @Column(name = "notes", columnDefinition = "TEXT")
    public String notes;

    @Column(name = "private_notes", columnDefinition = "TEXT")
    public String privateNotes;

    @Column(name = "submitted_at", nullable = false)
    public LocalDateTime submittedAt;

    @Column(name = "reopened_at")
    public LocalDateTime reopenedAt;

    @Column(name = "reopened_by_uuid", length = 36)
    public String reopenedByUuid;

    @Column(name = "reopened_reason", columnDefinition = "TEXT")
    public String reopenedReason;
}
