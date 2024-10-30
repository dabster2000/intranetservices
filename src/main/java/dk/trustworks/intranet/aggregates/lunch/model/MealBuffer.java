package dk.trustworks.intranet.aggregates.lunch.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "meal_buffer")
public class MealBuffer extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    public String id;

    @Column(name = "weekday", nullable = false)
    public String weekday;

    @Column(name = "buffer_meat", nullable = false)
    public String bufferMeat;

    @Column(name = "buffer_vegetarian", nullable = false)
    public String bufferVegetarian;

    @Column(name = "buffer_allergy_bowl", nullable = false)
    public String bufferAllergyBowl;

}
