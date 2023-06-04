package dk.trustworks.intranet.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * Created by hans on 23/06/2017.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "itbudget_category")
public class ItExpenseCategory extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    @Column(length = 25)
    private String name;

    private int lifespan;

    @Column(name = "long_name")
    private String longName;

    private String description;

}
