package dk.trustworks.intranet.aggregates.crm.calendar.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "calendar_sync_job")
@Getter
@Setter
public class CalendarSyncJob extends PanacheEntityBase {
    @Id @Column(length = 36) private String uuid;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "full_read", nullable = false) private boolean fullRead;
    @Column(name = "started_by", nullable = false, length = 36) private String startedBy;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "claimed_at") private LocalDateTime claimedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "summary_json", columnDefinition = "TEXT") private String summaryJson;
    @Column(name = "failure_code", length = 64) private String failureCode;
}
