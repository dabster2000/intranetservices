package dk.trustworks.intranet.aggregates.crm.plan.model;

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
 * What the plan and the account looked like when the last review closed (rule 9).
 *
 * <p>One row per plan — the latest. The tab reads it to answer "what has changed since we
 * last looked", which is the only question a review log is actually asked.
 *
 * <p>{@link #objectiveRags} and {@link #stakeholderIds} are JSON because a snapshot is a
 * frozen photograph read back whole and never queried by member. Everything live is
 * relational.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_plan_snapshot")
public class ClientPlanSnapshot extends PanacheEntityBase {

    @Id
    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", length = 10, nullable = false)
    private PlanRag health;

    /** {@code {"objective-uuid": "GREEN" | null}} — read back whole, never queried into. */
    @Column(name = "objective_rags", columnDefinition = "JSON", nullable = false)
    private String objectiveRags;

    @Column(name = "stakeholder_ids", columnDefinition = "JSON", nullable = false)
    private String stakeholderIds;

    @Column(name = "fact_weighted_rate")
    private Double factWeightedRate;

    @Column(name = "fact_consultants", nullable = false)
    private int factConsultants;

    @Column(name = "fact_open_leads", nullable = false)
    private int factOpenLeads;

    @Column(name = "fact_weighted_pipeline", nullable = false)
    private double factWeightedPipeline;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
