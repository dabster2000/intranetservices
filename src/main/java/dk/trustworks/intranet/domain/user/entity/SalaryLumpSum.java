package dk.trustworks.intranet.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "salary_lump_sum")
@EntityListeners(AuditEntityListener.class)
public class SalaryLumpSum extends PanacheEntityBase implements Auditable {
    @Id
    @Size(max = 36)
    @EqualsAndHashCode.Include
    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    private String useruuid;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "lump_sum", nullable = false)
    private Double lumpSum;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "salary_type", nullable = false)
    private LumpSumSalaryType salaryType;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "pension", nullable = false)
    private Boolean pension = false;

    @NotNull
    @Column(name = "month", nullable = false)
    private LocalDate month;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    @Column(name = "source_reference", length = 100)
    private String sourceReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    public static List<SalaryLumpSum> findByUser(String useruuid) {
        return SalaryLumpSum.find("useruuid", useruuid).list();
    }
}