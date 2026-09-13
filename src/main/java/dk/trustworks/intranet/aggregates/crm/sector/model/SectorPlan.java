package dk.trustworks.intranet.aggregates.crm.sector.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanRag;
import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanStatus;
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
 * The thin sector plan (sectors spec §3, V592): the same shape as {@code client_plan},
 * keyed by segment, minus the people.
 *
 * <p>It is a real record — four sentences, at most four objectives, the open actions, a
 * next review and a health — because the sector lead needs somewhere to say what the
 * sector is for. Everything else about the sector is derived from the accounts in it and
 * is never stored here.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sector_plan")
public class SectorPlan extends PanacheEntityBase {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20)
    private ClientSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private PlanStatus status;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "next_review")
    private LocalDate nextReview;

    /** Null is NOT ASSESSED — a real state, never a blank green (rule 8). */
    @Enumerated(EnumType.STRING)
    @Column(name = "health_rag", length = 10)
    private PlanRag healthRag;

    @Column(name = "health_why", length = 500)
    private String healthWhy;

    @Column(name = "health_set_by", length = 36)
    private String healthSetBy;

    @Column(name = "health_set_at")
    private LocalDateTime healthSetAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}
