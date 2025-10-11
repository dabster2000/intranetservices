package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

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

    @JsonIgnore
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "phaseuuid")
    private List<ConferencePhaseAttachment> attachments = new ArrayList<>();

    /**
     * Check if this phase has email attachments
     */
    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }
}
