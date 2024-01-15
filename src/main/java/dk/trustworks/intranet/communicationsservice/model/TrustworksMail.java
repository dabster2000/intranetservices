package dk.trustworks.intranet.communicationsservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

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

    public TrustworksMail(String uuid, String to, String subject, String body) {
        this.uuid = uuid;
        this.to = to;
        this.subject = subject;
        this.body = body;
    }
}
