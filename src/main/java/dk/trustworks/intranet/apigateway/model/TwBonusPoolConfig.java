package dk.trustworks.intranet.apigateway.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "tw_bonus_pool_config",
       uniqueConstraints = @UniqueConstraint(name = "uk_fy_company", columnNames = {"fiscal_year", "companyuuid"}))
public class TwBonusPoolConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    @NotNull
    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @NotNull
    @Column(name = "companyuuid", nullable = false, length = 36)
    private String companyuuid;

    @NotNull
    @Column(name = "profit_before_tax", nullable = false)
    private Double profitBeforeTax = 0.0;

    @NotNull
    @Column(name = "bonus_percent", nullable = false)
    private Double bonusPercent = 10.0;

    @NotNull
    @Column(name = "extra_pool", nullable = false)
    private Double extraPool = 0.0;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
