package dk.trustworks.intranet.cultureservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

import static javax.persistence.CascadeType.ALL;
import static javax.persistence.FetchType.EAGER;

@Data
@NoArgsConstructor
@Entity
@Table(name = "performance_groups")
public class PerformanceGroups extends PanacheEntityBase {

    @Id
    private String uuid;
    private String name;
    private boolean active;

    @OneToMany(cascade = ALL, fetch = EAGER)
    @JoinColumn(name = "pg_uuid")
    private List<PerformanceKey> performanceKeys = new ArrayList<>();

}
