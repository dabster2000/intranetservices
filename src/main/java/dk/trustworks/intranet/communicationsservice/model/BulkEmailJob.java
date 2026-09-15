package dk.trustworks.intranet.communicationsservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooter;
import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooterConverter;
import java.time.LocalDateTime;

/**
 * Represents a bulk email job that sends the same message to multiple recipients.
 * Processed asynchronously by JBeret batch job 'bulk-mail-send'.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "bulk_email_job")
public class BulkEmailJob extends PanacheEntityBase {

    @Convert(converter = UnsubscribeFooterConverter.class)
    @Column(name = "unsubscribe_footer", columnDefinition = "JSON")
    private UnsubscribeFooter unsubscribeFooter;

    @Column(name = "conference_uuid")
    private String conferenceUuid;

    @Column(name = "mail_origin")
    private String mailOrigin = "SYSTEM";

    @Column(name = "hold_reason")
    private String holdReason;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Id
    private String uuid;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BulkEmailJobStatus status = BulkEmailJobStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "total_recipients", nullable = false)
    private int totalRecipients = 0;

    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    public BulkEmailJob(String uuid, String subject, String body) {
        this.uuid = uuid;
        this.subject = subject;
        this.body = body;
        this.createdAt = LocalDateTime.now();
        this.status = BulkEmailJobStatus.PENDING;
    }

    public enum BulkEmailJobStatus {
        PENDING,
        POLICY_PENDING,
        POLICY_PROCESSING,
        HELD,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
