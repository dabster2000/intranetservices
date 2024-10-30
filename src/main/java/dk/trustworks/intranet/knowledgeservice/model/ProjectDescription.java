package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projectdescriptions")
public class ProjectDescription extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String clientuuid;

    private String name;

    private String purpose;

    private String role;

    private String learnings;

    private String roles;

    private String methods;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @Column(name = "active_from")
    private LocalDate fromDate;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @Column(name = "active_to")
    private LocalDate toDate;


    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "projectdesc_uuid", referencedColumnName="uuid")
    private List<ProjectDescriptionUser> projectDescriptionUserList;

    @JsonProperty("rolesList")
    public List<String> getRolesList() {
        if(this.roles==null || roles.isBlank()) return new ArrayList<>();
        String cleanedOffering = removeHashtags(this.roles);
        return List.of(cleanedOffering.split("\\s+"));
    }

    @JsonProperty("methodsList")
    public List<String> getMethodsList() {
        if(this.methods==null || methods.isBlank()) return new ArrayList<>();
        String cleanedTools = removeHashtags(this.methods);
        return List.of(cleanedTools.split("\\s+"));
    }

    private static String removeHashtags(String input) {
        return input.replaceAll("#", "");
    }


}
