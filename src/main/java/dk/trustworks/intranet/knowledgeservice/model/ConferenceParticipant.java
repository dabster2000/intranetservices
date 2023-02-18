package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceApplicationStatus;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jboss.resteasy.annotations.providers.multipart.PartType;

import javax.persistence.*;
import javax.ws.rs.FormParam;
import javax.ws.rs.core.MediaType;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conference_participants")
public class ConferenceParticipant extends PanacheEntityBase {

    @Id
    private String uuid;

    private String conferenceuuid;

    @Enumerated(EnumType.STRING)
    private ConferenceType type;

    @FormParam("name")
    @PartType(MediaType.TEXT_PLAIN)
    private String name;

    @FormParam("email")
    @PartType(MediaType.TEXT_PLAIN)
    private String email;

    @FormParam("company")
    @PartType(MediaType.TEXT_PLAIN)
    private String company;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clientuuid")
    private Client client;

    @Enumerated(EnumType.STRING)
    private ConferenceApplicationStatus status;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime registered;

    public ConferenceParticipant(String name, String email, String company, String conferenceuuid, ConferenceType conferenceType, ConferenceApplicationStatus status) {
        this.name = name;
        this.email = email;
        this.company = company;
        uuid = UUID.randomUUID().toString();
        registered = LocalDateTime.now();
        this.conferenceuuid = conferenceuuid;
        type = conferenceType;
        this.status = status;
    }
}
