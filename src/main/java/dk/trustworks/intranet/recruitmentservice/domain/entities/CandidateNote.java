package dk.trustworks.intranet.recruitmentservice.domain.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_candidate_note")
public class CandidateNote extends PanacheEntityBase {

    public enum Visibility { PRIVATE, SHARED, REJECTION_REASON }

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "candidate_uuid", length = 36, nullable = false)
    public String candidateUuid;

    @Column(name = "author_uuid", length = 36, nullable = false)
    public String authorUuid;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String body;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    public Visibility visibility;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    public CandidateNote() {}

    public static CandidateNote fresh(String candidateUuid, String authorUuid, String body, Visibility v) {
        CandidateNote n = new CandidateNote();
        n.uuid = UUID.randomUUID().toString();
        n.candidateUuid = candidateUuid;
        n.authorUuid = authorUuid;
        n.body = body;
        n.visibility = v;
        return n;
    }
}
