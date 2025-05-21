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
@Table(name = "buffer")
public class MealBuffer extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", columnDefinition = "char(36)", nullable = false)
    public String uuid;

    @Column(name = "weekday", nullable = false, length = 50)
    public String weekday;

    // If there was a foreign key meal_plan_buffer_uuid referencing MealPlanBuffer, it would be a ManyToOne relationship:
    // @ManyToOne
    // @JoinColumn(name = "meal_plan_buffer_uuid", referencedColumnName = "uuid", nullable = false)
    // public MealPlanBuffer mealPlanBuffer;
}