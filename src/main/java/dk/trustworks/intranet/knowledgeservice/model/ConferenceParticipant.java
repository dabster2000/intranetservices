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

    @FormParam("name")
    @PartType(MediaType.TEXT_PLAIN)
    private String name;

    @FormParam("company")
    @PartType(MediaType.TEXT_PLAIN)
    private String company;

    @FormParam("titel")
    @PartType(MediaType.TEXT_PLAIN)
    private String titel;

    @FormParam("email")
    @PartType(MediaType.TEXT_PLAIN)
    private String email;

    @FormParam("andet")
    @PartType(MediaType.TEXT_PLAIN)
    private String andet;

    @FormParam("samtykke")
    @PartType(MediaType.TEXT_PLAIN)
    private boolean samtykke;

    @Enumerated(EnumType.STRING)
    private ConferenceType type;

    @Enumerated(EnumType.STRING)
    private ConferenceApplicationStatus status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clientuuid")
    private Client client;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime registered;


    public ConferenceParticipant(String name, String company, String titel, String email, String andet, boolean samtykke, ConferenceType type, ConferenceApplicationStatus status) {
        this.uuid = UUID.randomUUID().toString();
        this.name = name;
        this.company = company;
        this.titel = titel;
        this.email = email;
        this.andet = andet;
        this.samtykke = samtykke;
        this.type = type;
        this.status = status;
        this.registered = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ConferenceParticipant that = (ConferenceParticipant) o;

        if (!conferenceuuid.equals(that.conferenceuuid)) return false;
        return email.equals(that.email);
    }

    @Override
    public int hashCode() {
        int result = conferenceuuid.hashCode();
        result = 31 * result + email.hashCode();
        return result;
    }
}

/*
    private String uuid;
    private String conferenceuuid;
    private String name;
    private String company;
    private String titel;
    private String email;
    private boolean samtykke;
    private ConferenceType type;
    private ConferenceApplicationStatus status;
    private Client client;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime registered;
 */