package dk.trustworks.intranet.cultureservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@NoArgsConstructor
@Entity
@Table(name = "lesson_roles")
public class LessonRole extends PanacheEntityBase {

    @Id
    private String uuid;
    private String name;

}
