package dk.trustworks.intranet.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.enums.TaskboardItemStatus;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.List;

import static javax.persistence.FetchType.EAGER;

@Data
@NoArgsConstructor
@Entity
@Table(name = "taskboard_items")
public class TaskboardItem extends PanacheEntityBase {

    @Id
    private String uuid;
    private String taskboarduuid;
    private String title;
    private String description;
    private String badges;
    private String businesscase;
    private String stakeholders;
    @Column(name = "expected_time")
    private String expectedtime;
    @ManyToOne(fetch = EAGER)
    @JoinColumn(name = "originator")
    private User originator;
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate deadline;
    @Enumerated(EnumType.STRING)
    private TaskboardItemStatus status;
    @OneToMany(mappedBy = "item", fetch = EAGER)
    @Fetch(FetchMode.SELECT)
    List<TaskboardItemChecklist> checklist;
    @OneToMany(fetch = EAGER)
    @JoinTable (
            name="taskboard_item_workers",
            joinColumns={ @JoinColumn(name="itemuuid", referencedColumnName="uuid") },
            inverseJoinColumns={ @JoinColumn(name="useruuid", referencedColumnName="uuid", unique=true) }
    )
    List<User> workers;

}
