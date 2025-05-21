package dk.trustworks.intranet.aggregates.lunch.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "meal_plan_user")
public class MealPlanUser extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", columnDefinition = "char(36)", nullable = false)
    public String uuid;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @ManyToOne
    @JoinColumn(name = "meal_plan_uuid", referencedColumnName = "uuid", nullable = false)
    public MealPlan mealPlan;

    @ManyToOne
    @JoinColumn(name = "meal_choice_uuid", referencedColumnName = "uuid", nullable = false)
    public MealChoice mealChoice;
}