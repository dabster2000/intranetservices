package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projectdescriptions")
public class ProjectDescription extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    private String clientuuid;

    private String name;

    @Lob
    private String description;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate from;

    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate to;

    private String offering;

    @Lob
    private String tools;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "projectdescid", referencedColumnName="id")
    private List<ProjectDescriptionUser> projectDescriptionUserList;

    @JsonProperty("offeringList")
    public List<String> getOfferingList() {
        System.out.println("ProjectDescription.getOfferingList");
        if(this.offering==null || offering.isBlank()) return new ArrayList<>();
        System.out.println("this.offering = " + this.offering);
        String cleanedOffering = removeHashtags(this.offering);
        System.out.println("cleanedOffering = " + cleanedOffering);
        return List.of(cleanedOffering.split("\\s+"));
    }

    @JsonProperty("toolsList")
    public List<String> getToolsList() {
        if(this.tools==null || tools.isBlank()) return new ArrayList<>();
        String cleanedTools = removeHashtags(this.tools);
        return List.of(cleanedTools.split("\\s+"));
    }

    private static String removeHashtags(String input) {
        return input.replaceAll("#", "");
    }


}
