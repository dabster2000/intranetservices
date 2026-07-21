package dk.trustworks.intranet.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A team-scoped key/value setting (V418). The only key today is
 * {@code it_budget} (re-homed from the retired {@code practice_settings}).
 * Audit fields follow the house {@link Auditable} pattern (V421); the
 * pre-existing {@code updated_by} column doubles as {@link Auditable}'s
 * modifiedBy so the wire format ({@code updatedBy}) stays stable.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "team_settings")
@EntityListeners(AuditEntityListener.class)
public class TeamSetting extends PanacheEntityBase implements Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String teamuuid;

    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "updated_by")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String updatedBy;

    public TeamSetting(String teamuuid, String settingKey, String settingValue, String updatedBy) {
        this.teamuuid = teamuuid;
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.updatedBy = updatedBy;
    }

    /** {@link Auditable}'s modifiedBy is stored in the pre-V421 {@code updated_by} column. */
    @Override
    @JsonIgnore
    public String getModifiedBy() {
        return updatedBy;
    }

    @Override
    @JsonIgnore
    public void setModifiedBy(String modifiedBy) {
        this.updatedBy = modifiedBy;
    }
}
