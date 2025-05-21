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
    @Column(name = "uuid", columnDefinition = "char(36)", nullable = false)
    public String uuid;

    @ManyToOne
    @JoinColumn(name = "meal_plan_uuid", referencedColumnName = "uuid", nullable = false)
    public MealPlan mealPlan;

    @ManyToOne
    @JoinColumn(name = "buffer_uuid", referencedColumnName = "uuid", nullable = false)
    public MealBuffer buffer;

    public MealPlanBuffer() {}

    public MealPlanBuffer(String uuid, MealPlan mealPlan, MealBuffer buffer) {
        this.uuid = uuid;
        this.mealPlan = mealPlan;
        this.buffer = buffer;
    }
}