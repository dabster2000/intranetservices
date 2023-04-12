package dk.trustworks.intranet.dao.bubbleservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.dao.bubbleservice.model.enums.BubbleType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.List;

@Data
@Entity
@NoArgsConstructor
@Table(name = "bubbles")
public class Bubble extends PanacheEntityBase {

    @Id
    private String uuid;
    private String name;
    @Enumerated(EnumType.STRING)
    private BubbleType type;
    private String description;
    private String application;
    private String slackchannel;
    private String owner;
    @Column(name = "co_owner")
    private String coowner;
    @Column(name = "meeting_form")
    private String meetingform;

    private String preconditions;
    private boolean active;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate created;

    @OneToMany(fetch = FetchType.EAGER, mappedBy = "bubble")
    private List<BubbleMember> bubbleMembers;

    // Only used on creation
    @Transient private String slackChannelName;

    public static Bubble findById(String uuid){
        return find("uuid", uuid).firstResult();
    }

}
