package dk.trustworks.intranet.aggregates.crm.sector.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionCadence;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionPriority;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ActionStatus;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One executable next step on a sector plan. A due date OR a cadence — one with neither
 * is a wish, not an action (rule 2), and {@code SectorPlanService} refuses it.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sector_plan_action")
public class SectorPlanAction extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20, nullable = false)
    private ClientSegment segment;

    @Column(name = "title", length = 300, nullable = false)
    private String title;

    @Column(name = "how", length = 500)
    private String how;

    @Column(name = "owner_uuid", length = 36, nullable = false)
    private String ownerUuid;

    @Column(name = "due")
    private LocalDate due;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", length = 15)
    private ActionCadence cadence;

    @Column(name = "next_due")
    private LocalDate nextDue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private ActionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 10, nullable = false)
    private ActionPriority priority;

    @Column(name = "objective_uuid", length = 36)
    private String objectiveUuid;

    @Column(name = "result", length = 1000)
    private String result;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "from_suggestion_id", length = 120)
    private String fromSuggestionId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}
