package dk.trustworks.intranet.aggregates.finance.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "career_level_bonus")
public class CareerLevelBonus extends PanacheEntityBase {

    @Id
    @Column(name = "career_level", length = 50, nullable = false)
    public String careerLevel;

    @Column(name = "bonus_pct", nullable = false, precision = 5, scale = 2)
    public BigDecimal bonusPct = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    public String updatedBy;
}
