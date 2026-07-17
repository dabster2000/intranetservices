package dk.trustworks.intranet.aggregates.invoice.bonus.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin override for the pool-basis excluded-salary set (per leader, per fiscal year).
 * At most one row per (fiscal year, user) — enforced by a unique constraint.
 */
@Getter
@Setter
@Entity
@Table(name = "teamlead_bonus_salary_exclusion",
        uniqueConstraints = @UniqueConstraint(name = "uq_teamlead_bonus_salary_exclusion_fy_user",
                columnNames = {"fiscal_year", "useruuid"}))
@Schema(name = "TeamleadBonusSalaryExclusion",
        description = "Admin override forcing a user into (EXCLUDE_SALARY) or out of (INCLUDE_SALARY) the excluded-salary group.")
public class TeamleadBonusSalaryExclusion extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", nullable = false, length = 36)
    public String uuid;

    @Column(name = "fiscal_year", nullable = false)
    public int fiscalYear;

    @Column(name = "useruuid", nullable = false, length = 36)
    public String useruuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    public SalaryExclusionMode mode;

    @Column(name = "note", length = 500)
    public String note;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    public String createdBy;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    public String updatedBy;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static List<TeamleadBonusSalaryExclusion> listByFiscalYear(int fiscalYear) {
        return list("fiscalYear = ?1 order by useruuid", fiscalYear);
    }

    public static TeamleadBonusSalaryExclusion findByFiscalYearAndUser(int fiscalYear, String useruuid) {
        return find("fiscalYear = ?1 and useruuid = ?2", fiscalYear, useruuid).firstResult();
    }
}
