package dk.trustworks.intranet.communicationsservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

/**
 * Represents an email attachment for a bulk email job.
 * Attachments are stored once per job and reused for all recipients.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "bulk_email_attachment")
public class BulkEmailAttachment extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_uuid", nullable = false)
    private String jobUuid;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] content;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    public BulkEmailAttachment(String jobUuid, String filename, String contentType, byte[] content) {
        this.jobUuid = jobUuid;
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
        this.fileSize = content != null ? content.length : 0;
    }

    /**
     * Convert to EmailAttachment DTO for sending
     */
    public EmailAttachment toEmailAttachment() {
        return new EmailAttachment(filename, contentType, content);
    }
}
