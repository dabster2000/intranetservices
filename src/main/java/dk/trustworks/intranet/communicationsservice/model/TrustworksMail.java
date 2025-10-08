package dk.trustworks.intranet.communicationsservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Table(name = "mail")
public class TrustworksMail extends PanacheEntityBase {
    @Id
    private String uuid;
    @Column(name = "mail")
    private String to;
    private String subject;
    @Column(name = "content")
    private String body;
    @JsonIgnore
    @Enumerated(EnumType.STRING)
    private MailStatus status;

    /**
     * Transient field for email attachments - not persisted to database.
     * Emails with attachments are sent immediately rather than queued.
     */
    @Transient
    private List<EmailAttachment> attachments = new ArrayList<>();

    public TrustworksMail(String uuid, String to, String subject, String body) {
        this.uuid = uuid;
        this.to = to;
        this.subject = subject;
        this.body = body;
    }

    /**
     * Check if this email has attachments
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }
}
