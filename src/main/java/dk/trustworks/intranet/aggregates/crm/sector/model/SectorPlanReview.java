package dk.trustworks.intranet.aggregates.crm.sector.model;

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
 * One closed review of a sector plan (rule 9). Closing bumps the plan version and writes a
 * {@link SectorPlanSnapshot}. Attendees and decisions are pure link tables with no
 * behaviour and, as on the account plan, have no entity.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sector_plan_review")
public class SectorPlanReview extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20, nullable = false)
    private ClientSegment segment;

    @Column(name = "review_date", nullable = false)
    private LocalDate reviewDate;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "outcome", length = 1000, nullable = false)
    private String outcome;

    @Column(name = "next_review")
    private LocalDate nextReview;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}
