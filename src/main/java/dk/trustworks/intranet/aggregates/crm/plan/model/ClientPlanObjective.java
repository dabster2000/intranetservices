package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.MeasureKind;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.ObjectiveCategory;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
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
 * One objective on a plan. At most four per plan — {@code AccountPlanService} refuses the
 * fifth, because a plan with eight objectives is a list, not a plan, and the fifth is
 * never read anyway.
 *
 * <p><b>The measure is a small language, not a number.</b> {@link MeasureKind#RATE} and
 * {@link MeasureKind#CONSULTANTS} objectives read their CURRENT value live from the page's
 * real contracts, so the target is set once and the number behind it is never retyped and
 * never goes stale. {@code NUMBER} is a figure only a person can update. {@code TEXT} has
 * no number at all — only a colour and the reason for it.
 *
 * <p>{@link #baseline} and {@link #target} are varchar rather than numeric so a TEXT
 * objective can say "two named references" without the column lying about its type.
 *
 * <p>{@link #rag} null means not assessed; {@link #ragWhy} is required for a green.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan_objective")
public class ClientPlanObjective extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** 1-4, the order the tab shows them in. */
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

    /** True when the target is a floor to hold ("stay above 1,240"), not a level to reach. */
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

    /**
     * The sector objective this one SERVES ({@code sector_plan_objective.uuid}, V592) — the
     * one typed connection between an account plan and its sector plan. Optional, a soft
     * reference, and only ever set to an open objective of the client's own segment.
     */
    @Column(name = "sector_objective_uuid", length = 36)
    private String sectorObjectiveUuid;

    /** Set when the objective is closed as achieved; it then leaves the live list. */
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
