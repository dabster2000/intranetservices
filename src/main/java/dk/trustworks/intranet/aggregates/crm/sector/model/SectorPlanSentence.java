package dk.trustworks.intranet.aggregates.crm.sector.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One of the four sentences of a sector plan. The slot decides the sentence's epistemic
 * kind — nobody picks it — exactly as on the account plan.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@IdClass(SectorPlanSentenceId.class)
@Table(name = "sector_plan_sentence")
public class SectorPlanSentence extends PanacheEntityBase {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20)
    private ClientSegment segment;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "slot", length = 10)
    private PlanSlot slot;

    @Column(name = "sentence_text", length = 1000, nullable = false)
    private String text;

    /** Who wrote it — not who last confirmed the plan. */
    @Column(name = "by_uuid", length = 36, nullable = false)
    private String byUuid;

    @Column(name = "validated_at", nullable = false)
    private LocalDateTime validatedAt;
}
