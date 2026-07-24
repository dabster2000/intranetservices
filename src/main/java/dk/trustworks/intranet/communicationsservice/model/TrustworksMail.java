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

    /**
     * Optional Reply-To address. Persisted since V455, so BOTH send paths
     * honour it — the immediate one
     * ({@link dk.trustworks.intranet.communicationsservice.resources.MailResource#sendWithAttachments})
     * and the queued JBeret {@code mail-send} job. Null = no Reply-To
     * header, which is what every pre-V455 caller keeps.
     */
    @Column(name = "reply_to")
    private String replyTo;

    /**
     * Optional display name rendered in front of the configured
     * {@code quarkus.mailer.from} address ("Trustworks Rekruttering
     * &lt;no-reply@trustworks.dk&gt;"). The envelope address itself is
     * never overridden: SES verifies sender identities, and a per-person
     * From would risk a 554 rejection. Null = the bare configured address.
     */
    @Column(name = "from_name")
    private String fromName;

    /** Comma-separated visible copies; null or blank = none. */
    @Column(name = "cc")
    private String cc;

    /** Comma-separated invisible copies; null or blank = none. */
    @Column(name = "bcc")
    private String bcc;

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
