package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projectdescription_users")
public class ProjectDescriptionUser extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    private String useruuid;

    @JsonIgnore
    @ManyToOne()
    @JoinColumn(name="projectdesc_uuid")
    private ProjectDescription projectDescription;

    public ProjectDescriptionUser(String useruuid, ProjectDescription projectDescription) {
        this.useruuid = useruuid;
        this.projectDescription = projectDescription;
    }
}
