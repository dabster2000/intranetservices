package dk.trustworks.intranet.aggregates.lunch.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "meal_plan_buffer")
public class MealPlanBuffer extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    public String id;

    @ManyToOne
    @JoinColumn(name = "meal_plan_id", referencedColumnName = "id", nullable = false)
    public MealPlan mealPlan;

    @ManyToOne
    @JoinColumn(name = "buffer_id", referencedColumnName = "id", nullable = false)
    public MealBuffer buffer;

    public MealPlanBuffer() {}

    public MealPlanBuffer(String id, MealPlan mealPlan, MealBuffer buffer) {
        this.id = id;
        this.mealPlan = mealPlan;
        this.buffer = buffer;
    }
}
