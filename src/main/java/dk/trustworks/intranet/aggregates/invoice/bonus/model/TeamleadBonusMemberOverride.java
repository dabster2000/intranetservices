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
 * Admin override for whether a single team member counts in the teamlead-bonus calculation of one
 * month. {@link #included} {@code true} force-includes the member-month (e.g. a full-leave member
 * that would otherwise vanish), {@code false} force-excludes it (removing the member's hours from
 * team utilization, their headcount from the average team size and their revenue from the pool
 * basis). At most one row per (team, user, month) — enforced by a unique constraint (V415).
 */
@Getter
@Setter
@Entity
@Table(name = "teamlead_bonus_member_override",
        uniqueConstraints = @UniqueConstraint(name = "uq_teamlead_bonus_member_override_team_user_month",
                columnNames = {"teamuuid", "useruuid", "month"}))
@Schema(name = "TeamleadBonusMemberOverride",
        description = "Admin override forcing a member-month into or out of the teamlead bonus calculation.")
public class TeamleadBonusMemberOverride extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", nullable = false, length = 36)
    public String uuid;

    @Column(name = "teamuuid", nullable = false, length = 36)
    public String teamuuid;

    @Column(name = "useruuid", nullable = false, length = 36)
    public String useruuid;

    /** Fiscal-month key in {@code YYYYMM} form, matching the {@code fact_user_day} month keys. */
    @Column(name = "month", nullable = false, length = 6)
    public String month;

    /** {@code true} = force-include the member-month, {@code false} = force-exclude it. */
    @Column(name = "included", nullable = false)
    public boolean included;

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

    public static TeamleadBonusMemberOverride findByTeamUserMonth(String teamuuid, String useruuid, String month) {
        return find("teamuuid = ?1 and useruuid = ?2 and month = ?3", teamuuid, useruuid, month).firstResult();
    }

    public static List<TeamleadBonusMemberOverride> listByTeamAndMonths(String teamuuid, List<String> months) {
        if (months == null || months.isEmpty()) return List.of();
        return list("teamuuid = ?1 and month in ?2", teamuuid, months);
    }

    public static List<TeamleadBonusMemberOverride> listByMonths(List<String> months) {
        if (months == null || months.isEmpty()) return List.of();
        return list("month in ?1", months);
    }
}
