package dk.trustworks.intranet.aggregates.crm.sector.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.MeasureKind;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ObjectiveCategory;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
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
 * One sector objective — the thing an account objective may declare it SERVES
 * (sectors spec §3.2, V592).
 *
 * <p>{@link #rag} is the sector lead's own assessment and is nullable — not assessed is a
 * real state. The DERIVED colour (the worst of the account objectives serving this one) is
 * computed by {@code SectorPlanService} on every read and never stored, because a stored
 * derived value is a derived value that goes stale.
 *
 * <p>Closed, never deleted: {@code client_plan_objective.sector_objective_uuid} is a soft
 * reference and a deleted row would leave an account objective pointing at nothing.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sector_plan_objective")
public class SectorPlanObjective extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20, nullable = false)
    private ClientSegment segment;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20, nullable = false)
    private ObjectiveCategory category;

    @Column(name = "title", length = 300, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "measure_kind", length = 20, nullable = false)
    private MeasureKind measureKind;

    @Column(name = "measure_label", length = 200)
    private String measureLabel;

    @Column(name = "baseline", length = 80)
    private String baseline;

    @Column(name = "target", length = 80)
    private String target;

    @Column(name = "unit", length = 40)
    private String unit;

    @Column(name = "hold", nullable = false)
    private boolean hold;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "owner_uuid", length = 36, nullable = false)
    private String ownerUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "rag", length = 10)
    private PlanRag rag;

    @Column(name = "rag_why", length = 500)
    private String ragWhy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}
