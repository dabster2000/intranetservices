package dk.trustworks.intranet.communicationsservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents an individual recipient within a bulk email job.
 * Each recipient has independent status tracking.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "bulk_email_recipient")
public class BulkEmailRecipient extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_uuid", nullable = false)
    private String jobUuid;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecipientStatus status = RecipientStatus.PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public BulkEmailRecipient(String jobUuid, String recipientEmail) {
        this.jobUuid = jobUuid;
        this.recipientEmail = recipientEmail;
        this.status = RecipientStatus.PENDING;
    }

    public enum RecipientStatus {
        PENDING,
        SENT,
        FAILED
    }
}
