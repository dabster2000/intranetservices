package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import jakarta.persistence.*;
import java.util.List;

@Data
@Entity
@Table(name = "conferences")
public class Conference extends PanacheEntityBase {
    @Id
    private String uuid;
    private String name;
    private String slug;
    private boolean active;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "conferenceuuid")
    private List<ConferencePhase> phases;
}
