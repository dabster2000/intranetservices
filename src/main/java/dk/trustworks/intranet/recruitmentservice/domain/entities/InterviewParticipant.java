package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantInvitationStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recruitment_interview_participant",
       uniqueConstraints = @UniqueConstraint(columnNames = {"interview_uuid", "user_uuid"}))
@Data
@NoArgsConstructor
public class InterviewParticipant extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    public String uuid;

    @Column(name = "interview_uuid", length = 36, nullable = false)
    public String interviewUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    public String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_in_interview", length = 30, nullable = false)
    public ParticipantRole roleInInterview;

    @Column(name = "is_required_scorer", nullable = false)
    public Boolean isRequiredScorer;

    @Enumerated(EnumType.STRING)
    @Column(name = "invitation_status", length = 20, insertable = false)
    public ParticipantInvitationStatus invitationStatus;
}
