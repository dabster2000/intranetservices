package dk.trustworks.intranet.userservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
