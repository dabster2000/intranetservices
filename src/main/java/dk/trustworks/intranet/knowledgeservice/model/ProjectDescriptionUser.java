package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projectdescription_users")
public class ProjectDescriptionUser extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private String id;

    private String useruuid;

    @Lob
    private String description;

    @JsonIgnore
    private int projectdescid;
}
