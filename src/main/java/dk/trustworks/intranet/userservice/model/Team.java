package dk.trustworks.intranet.userservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@Entity
@Table(name = "team")
@NoArgsConstructor
public class Team extends PanacheEntityBase {

    @Id
    private String uuid;
    private String name;
    private String shortname;
    private String logouuid;
    private boolean teamleadbonus;
    private boolean teambonus;

}
