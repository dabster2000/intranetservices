package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projectdescriptions")
@EntityListeners(AuditEntityListener.class)
public class ProjectDescription extends PanacheEntityBase implements Auditable {

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

    @Column(name = "created_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, length = 255)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by", length = 255)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @OneToMany(fetch = FetchType.EAGER, mappedBy = "projectDescription", cascade = CascadeType.REMOVE)
    private List<ProjectDescriptionUser> projectDescriptionUserList;

    @JsonProperty("rolesList")
    public Set<String> getRolesList() {
        if(this.roles==null || roles.isBlank()) return new HashSet<>();
        return parseAndSortHashtags(this.roles);
    }

    @JsonProperty("methodsList")
    public Set<String> getMethodsList() {
        if(this.methods==null || methods.isBlank()) return new HashSet<>();
        return parseAndSortHashtags(this.methods);
    }

    public static Set<String> parseAndSortHashtags(String input) {
        Set<String> result = new TreeSet<>();

        if (input == null || input.isEmpty()) {
            return result;
        }

        // Split the input string by hashtags
        String[] parts = input.split("#");

        for (String part : parts) {
            // Trim each part and ignore any empty or whitespace-only strings
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

}
