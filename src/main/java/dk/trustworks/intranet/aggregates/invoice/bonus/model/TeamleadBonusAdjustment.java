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
 * Admin-entered adjustment for one leader in one fiscal year. Three kinds coexist on the same
 * table, discriminated by {@link #adjustmentType}: split bonuses and prepaid deductions carry an
 * {@link #amount}; a utilization override carries {@link #utilOverride} instead.
 */
@Getter
@Setter
@Entity
@Table(name = "teamlead_bonus_adjustment")
@Schema(name = "TeamleadBonusAdjustment",
        description = "Admin-entered teamlead bonus adjustment (split bonus / prepaid deduction / utilization override).")
public class TeamleadBonusAdjustment extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", nullable = false, length = 36)
    public String uuid;

    @Column(name = "fiscal_year", nullable = false)
    public int fiscalYear;

    @Column(name = "useruuid", nullable = false, length = 36)
    public String useruuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 30)
    public TeamleadAdjustmentType adjustmentType;

    @Column(name = "amount")
    public Double amount;

    @Column(name = "util_override")
    public Double utilOverride;

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

    public static List<TeamleadBonusAdjustment> listByFiscalYear(int fiscalYear) {
        return list("fiscalYear = ?1 order by useruuid, createdAt", fiscalYear);
    }

    public static List<TeamleadBonusAdjustment> listByFiscalYearAndUser(int fiscalYear, String useruuid) {
        return list("fiscalYear = ?1 and useruuid = ?2", fiscalYear, useruuid);
    }
}
