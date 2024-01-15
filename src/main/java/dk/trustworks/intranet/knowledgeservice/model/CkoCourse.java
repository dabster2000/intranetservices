package dk.trustworks.intranet.knowledgeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Entity
@Table(name = "cko_courses")
public class CkoCourse extends PanacheEntityBase {

    @Id
    private String uuid;
    private String name;
    private String description;
    private String type;
    private String owner;
    private boolean active;
    @JsonIgnore
    private LocalDate created;

    public CkoCourse() {
        this.uuid = UUID.randomUUID().toString();
        this.created = LocalDate.now();
        this.active = true;
    }
}
