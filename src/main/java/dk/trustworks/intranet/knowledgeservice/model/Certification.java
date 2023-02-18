package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@NoArgsConstructor
@Entity
@Table(name = "certifications")
public class Certification extends PanacheEntityBase {

    @Id
    private String uuid;

    private String name;

}
