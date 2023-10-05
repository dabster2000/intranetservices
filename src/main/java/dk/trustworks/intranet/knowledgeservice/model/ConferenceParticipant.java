package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.dao.crm.model.Client;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conference_participants")
public class ConferenceParticipant extends PanacheEntityBase {

    @Id
    private String uuid;
    private String participantuuid;
    private String conferenceuuid;
    private String name;
    private String company;
    private String titel;
    private String email;
    private String andet;
    private boolean samtykke;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "phaseuuid")
    private ConferencePhase conferencePhase;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clientuuid")
    private Client client;
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime registered;

    public ConferenceParticipant(String name, String company, String titel, String email, String andet, boolean samtykke) {
        this.name = name;
        this.company = company;
        this.titel = titel;
        this.email = email;
        this.andet = andet;
        this.samtykke = samtykke;
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
