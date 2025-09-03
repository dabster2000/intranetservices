package dk.trustworks.intranet.domain.user.entity;

import dk.trustworks.intranet.userservice.model.enums.SalarySupplementType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "salary_supplement")
public class SalarySupplement extends PanacheEntityBase {
    @Id
    @Size(max = 36)
    @Column(name = "uuid", nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private String uuid;

    private String useruuid;

    @Size(max = 10)
    @NotNull
    @Enumerated(EnumType.STRING)
    @ColumnDefault("SUM")
    @Column(name = "type", nullable = false, length = 10)
    private SalarySupplementType type;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "value", nullable = false)
    private Double value;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "with_pension", nullable = false)
    private Boolean withPension = false;

    @NotNull
    @Column(name = "from_month", nullable = false)
    private LocalDate fromMonth;

    @Column(name = "to_month")
    private LocalDate toMonth;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    public static List<SalarySupplement> findByUseruuid(String useruuid) {
        return SalarySupplement.find("useruuid", useruuid).list();
    }
}