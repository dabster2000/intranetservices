package dk.trustworks.intranet.aggregates.crm.sector.model;

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
 * What the sector and its plan looked like when the last review closed (rule 9). One row
 * per segment — the latest. The three JSON columns are frozen photographs read back
 * whole, never queried by member.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sector_plan_snapshot")
public class SectorPlanSnapshot extends PanacheEntityBase {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20)
    private ClientSegment segment;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", length = 10, nullable = false)
    private PlanRag health;

    @Column(name = "objective_rags", columnDefinition = "JSON", nullable = false)
    private String objectiveRags;

    @Column(name = "fact_fy_revenue", nullable = false)
    private double factFyRevenue;

    @Column(name = "fact_weighted_rate")
    private Double factWeightedRate;

    @Column(name = "fact_consultants", nullable = false)
    private int factConsultants;

    @Column(name = "fact_open_leads", nullable = false)
    private int factOpenLeads;

    @Column(name = "fact_weighted_pipeline", nullable = false)
    private double factWeightedPipeline;

    @Column(name = "fact_accounts_by_band", columnDefinition = "JSON", nullable = false)
    private String factAccountsByBand;

    @Column(name = "fact_plan_coverage", columnDefinition = "JSON", nullable = false)
    private String factPlanCoverage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
