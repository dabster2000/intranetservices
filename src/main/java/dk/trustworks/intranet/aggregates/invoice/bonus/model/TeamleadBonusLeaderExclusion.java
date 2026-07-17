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
 * Admin exclusion of one leader from one team's teamlead bonus for a fiscal year. The excluded
 * leader's payable slice goes unpaid and is NOT redistributed to the team's other leaders; when
 * every leader of a team is excluded the whole team is removed from the program (its points leave
 * Σpoints and its members' revenue leaves the pool basis). At most one row per
 * (fiscal year, team, user) — enforced by a unique constraint (V415).
 */
@Getter
@Setter
@Entity
@Table(name = "teamlead_bonus_leader_exclusion",
        uniqueConstraints = @UniqueConstraint(name = "uq_teamlead_bonus_leader_exclusion_fy_team_user",
                columnNames = {"fiscal_year", "teamuuid", "useruuid"}))
@Schema(name = "TeamleadBonusLeaderExclusion",
        description = "Admin exclusion of a leader from a team's teamlead bonus for a fiscal year.")
public class TeamleadBonusLeaderExclusion extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", nullable = false, length = 36)
    public String uuid;

    @Column(name = "fiscal_year", nullable = false)
    public int fiscalYear;

    @Column(name = "teamuuid", nullable = false, length = 36)
    public String teamuuid;

    @Column(name = "useruuid", nullable = false, length = 36)
    public String useruuid;

    @Column(name = "note", length = 500)
    public String note;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    @Column(name = "created_by", length = 100)
    public String createdBy;

    @PrePersist
    public void prePersist() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public static List<TeamleadBonusLeaderExclusion> listByFiscalYear(int fiscalYear) {
        return list("fiscalYear = ?1 order by teamuuid, useruuid", fiscalYear);
    }

    public static TeamleadBonusLeaderExclusion findByFiscalYearTeamUser(int fiscalYear, String teamuuid, String useruuid) {
        return find("fiscalYear = ?1 and teamuuid = ?2 and useruuid = ?3", fiscalYear, teamuuid, useruuid).firstResult();
    }
}
