package dk.trustworks.intranet.aggregates.bugreport.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "bug_report_notifications")
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class BugReportNotification extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    @Column(name = "report_uuid", nullable = false, length = 36)
    private String reportUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static BugReportNotification create(String userUuid, String reportUuid,
                                                NotificationType type, String message) {
        var notification = new BugReportNotification();
        notification.uuid = UUID.randomUUID().toString();
        notification.userUuid = userUuid;
        notification.reportUuid = reportUuid;
        notification.type = type;
        notification.message = message;
        notification.read = false;
        notification.createdAt = LocalDateTime.now();
        return notification;
    }

    public void markAsRead() {
        this.read = true;
    }

    @PrePersist
    private void onPrePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
