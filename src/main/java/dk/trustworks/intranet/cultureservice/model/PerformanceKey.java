package dk.trustworks.intranet.cultureservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@NoArgsConstructor
@Entity
@Table(name = "performance_keys")
public class PerformanceKey extends PanacheEntityBase {

    @Id
    private String uuid;
    private String pg_uuid;
    private String name;
    private String description;

}
