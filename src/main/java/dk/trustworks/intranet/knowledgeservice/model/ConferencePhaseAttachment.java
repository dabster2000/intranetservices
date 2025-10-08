package dk.trustworks.intranet.knowledgeservice.model;

import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents a file attachment for a conference phase email.
 * Attachments are stored once per phase and sent to all participants
 * when they transition to this phase.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "conference_phase_attachments")
public class ConferencePhaseAttachment extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phaseuuid", nullable = false)
    private String phaseuuid;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] content;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ConferencePhaseAttachment(String phaseuuid, String filename, String contentType, byte[] content) {
        this.phaseuuid = phaseuuid;
        this.filename = filename;
        this.contentType = contentType;
        this.content = content;
        this.fileSize = content != null ? content.length : 0;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Convert to EmailAttachment DTO for sending via email
     */
    public EmailAttachment toEmailAttachment() {
        return new EmailAttachment(filename, contentType, content);
    }
}
