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
@Table(name = "performance_results")
public class PerformanceResult extends PanacheEntityBase {

    @Id
    private String uuid;
    private String pk_uuid;
    private int result;

}
