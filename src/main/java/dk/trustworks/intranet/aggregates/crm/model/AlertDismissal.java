package dk.trustworks.intranet.aggregates.crm.model;

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

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@NoArgsConstructor
@Entity
@Table(name = "alert_dismissals")
public class AlertDismissal extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", columnDefinition = "char(36)", nullable = false)
    public String uuid;

    @Column(name = "user_uuid", columnDefinition = "char(36)", nullable = false)
    public String userUuid;

    @Column(name = "alert_id", nullable = false, length = 255)
    public String alertId;

    @Column(name = "dismissed_at", nullable = false)
    public LocalDateTime dismissedAt;

    public AlertDismissal(String uuid, String userUuid, String alertId, LocalDateTime dismissedAt) {
        this.uuid = uuid;
        this.userUuid = userUuid;
        this.alertId = alertId;
        this.dismissedAt = dismissedAt;
    }

    public static List<AlertDismissal> findByUserUuid(String userUuid) {
        return find("userUuid", userUuid).list();
    }

    public static boolean existsByUserUuidAndAlertId(String userUuid, String alertId) {
        return count("userUuid = ?1 and alertId = ?2", userUuid, alertId) > 0;
    }

    public static void deleteByUserUuidAndAlertId(String userUuid, String alertId) {
        delete("userUuid = ?1 and alertId = ?2", userUuid, alertId);
    }
}
