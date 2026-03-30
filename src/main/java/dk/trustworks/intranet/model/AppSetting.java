package dk.trustworks.intranet.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "app_settings")
public class AppSetting extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;

    private String category;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public AppSetting(String settingKey, String settingValue, String category, String updatedBy) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.category = category;
        this.updatedBy = updatedBy;
        this.updatedAt = LocalDateTime.now();
    }
}
