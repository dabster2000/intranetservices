package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "budgets")
public class Budget extends PanacheEntityBase {

    @Id
    private String uuid;
    private int month;
    private int year;
    private Double budget;
    private String consultantuuid;
    private String projectuuid;

}
