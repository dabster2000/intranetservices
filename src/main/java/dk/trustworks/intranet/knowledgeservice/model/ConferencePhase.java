package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conference_phases")
public class ConferencePhase extends PanacheEntityBase {
    @Id
    private String uuid;
    private String conferenceuuid;
    private int step;
    private String name;
    @Column(name = "use_mail")
    private boolean useMail;
    private String subject;
    private String mail;
}
