package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_application_stage_history")
public class ApplicationStageHistory extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "application_uuid", length = 36, nullable = false)
    public String applicationUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 40)
    public ApplicationStage fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", length = 40, nullable = false)
    public ApplicationStage toStage;

    @Column(columnDefinition = "TEXT")
    public String reason;

    @Column(name = "actor_uuid", length = 36, nullable = false)
    public String actorUuid;

    @Column(name = "at", nullable = false)
    public LocalDateTime at = LocalDateTime.now();

    public ApplicationStageHistory() {}

    public static ApplicationStageHistory entry(String applicationUuid, ApplicationStage from,
                                                ApplicationStage to, String reason, String actor) {
        ApplicationStageHistory h = new ApplicationStageHistory();
        h.uuid = UUID.randomUUID().toString();
        h.applicationUuid = applicationUuid;
        h.fromStage = from;
        h.toStage = to;
        h.reason = reason;
        h.actorUuid = actor;
        return h;
    }
}
