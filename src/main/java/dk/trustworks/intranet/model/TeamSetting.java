package dk.trustworks.intranet.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A team-scoped key/value setting (V418). The only key today is
 * {@code it_budget} (re-homed from the retired {@code practice_settings}).
 * {@code updatedAt} is DB-managed (DEFAULT CURRENT_TIMESTAMP ON UPDATE
 * CURRENT_TIMESTAMP), so it is mapped read-only.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "team_settings")
public class TeamSetting extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String teamuuid;

    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public TeamSetting(String teamuuid, String settingKey, String settingValue, String updatedBy) {
        this.teamuuid = teamuuid;
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.updatedBy = updatedBy;
    }
}
