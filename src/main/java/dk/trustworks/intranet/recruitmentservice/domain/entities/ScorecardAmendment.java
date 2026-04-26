package dk.trustworks.intranet.recruitmentservice.domain.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "recruitment_scorecard_amendment")
@Data
@NoArgsConstructor
public class ScorecardAmendment extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    public String uuid;

    @Column(name = "scorecard_uuid", length = 36, nullable = false)
    public String scorecardUuid;

    @Column(name = "author_uuid", length = 36, nullable = false)
    public String authorUuid;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    public String body;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;
}
