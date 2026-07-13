package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "individual_bonus_reconciliation_head")
@IdClass(IndividualBonusReconciliationHead.Key.class)
public class IndividualBonusReconciliationHead extends PanacheEntityBase {
    @Id
    @Column(name = "rule_uuid", nullable = false, length = 36)
    private String ruleUuid;
    @Id
    @Column(name = "earning_month", nullable = false)
    private LocalDate earningMonth;
    @Column(name = "latest_revision", nullable = false)
    private Integer latestRevision = 0;
    @Column(name = "open_adjustment_uuid", length = 36)
    private String openAdjustmentUuid;
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA id classes must expose a no-argument constructor.  A record is tempting here, but it is not a
     * portable {@link IdClass}: providers are allowed to instantiate it reflectively before assigning the
     * two id attributes.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String ruleUuid;
        private LocalDate earningMonth;
    }
}
