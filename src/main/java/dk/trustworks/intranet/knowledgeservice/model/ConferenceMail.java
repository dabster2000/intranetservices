package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conference_mails")
public class ConferenceMail extends PanacheEntityBase {
    @Id
    private String uuid;
    private String conferenceuuid;
    private String subject;
    private String body;
}
