package dk.trustworks.intranet.competenceservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The visible, exportable settings change log.
 *
 * <p>{@code app_settings} keeps only the current value, but both the pass threshold and
 * the cadence change what a green cell means — so what they used to be, and who changed
 * them when, is itself evidence. Append-only.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "competence_settings_audit")
public class CompetenceSettingsAudit extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    public static List<CompetenceSettingsAudit> listRecent(int limit) {
        return find("order by changedAt desc").page(0, limit).list();
    }
}
