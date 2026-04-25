package dk.trustworks.intranet.recruitmentservice.domain.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_candidate_activity")
public class CandidateActivity extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "candidate_uuid", length = 36, nullable = false)
    public String candidateUuid;

    @Column(length = 60, nullable = false)
    public String kind;

    @Column(columnDefinition = "JSON")
    public String payload;

    @Column(name = "actor_uuid", length = 36)
    public String actorUuid;

    @Column(nullable = false)
    public LocalDateTime at = LocalDateTime.now();

    public CandidateActivity() {}

    public static CandidateActivity log(String candidateUuid, String kind, String payloadJson, String actor) {
        CandidateActivity a = new CandidateActivity();
        a.uuid = UUID.randomUUID().toString();
        a.candidateUuid = candidateUuid;
        a.kind = kind;
        a.payload = payloadJson;
        a.actorUuid = actor;
        return a;
    }
}
