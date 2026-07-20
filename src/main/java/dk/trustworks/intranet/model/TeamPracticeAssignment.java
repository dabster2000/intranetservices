package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.security.AuditEntityListener;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Temporal team↔practice association (V425, Part 2 Phase 1). The source of truth
 * for "which practice a team belonged to, when"; {@code team.practice_uuid} is
 * the denormalized current value. Mirrors the temporal {@code teamroles} /
 * {@code practice_lead} idiom: {@code enddate = null} means "current".
 * <p>
 * Phase 1 only <b>seeds and maps</b> this table — one open row per
 * practice-assigned team. Nothing writes it until Phase 2's
 * {@code PracticeSyncService} records team-practice transitions here. Audit
 * fields follow the house {@link Auditable} pattern (V421), populated by
 * {@link AuditEntityListener} from the X-Requested-By header.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "team_practice_assignment")
@EntityListeners(AuditEntityListener.class)
public class TeamPracticeAssignment extends PanacheEntityBase implements Auditable {

    @Id
    private String uuid;

    @Column(name = "team_uuid")
    private String teamUuid;

    @Column(name = "practice_uuid")
    private String practiceUuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate startdate;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate enddate;

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

    public TeamPracticeAssignment(String uuid, String teamUuid, String practiceUuid, LocalDate startdate, LocalDate enddate) {
        this.uuid = uuid;
        this.teamUuid = teamUuid;
        this.practiceUuid = practiceUuid;
        this.startdate = startdate;
        this.enddate = enddate;
    }
}
