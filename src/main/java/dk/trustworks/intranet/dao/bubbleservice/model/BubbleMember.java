package dk.trustworks.intranet.dao.bubbleservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "bubble_members")
public class BubbleMember extends PanacheEntityBase {

    @Id
    private String uuid;

    private String useruuid;

    @JsonIgnore
    @ManyToOne()
    @JoinColumn(name="bubbleuuid")
    private Bubble bubble;

}
