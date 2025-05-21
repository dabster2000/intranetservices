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
@Table(name = "meal_choice")
public class MealChoice extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", columnDefinition = "char(36)", nullable = false)
    public String uuid;

    @Column(name = "weekday", nullable = false, length = 50)
    public String weekday;

    @Column(name = "selected_meal_type", nullable = false)
    public String selectedMealType;

    @Column(name = "reserve_meat", nullable = false)
    public boolean reserveMeat;

    @Column(name = "reserve_vegetarian", nullable = false)
    public boolean reserveVegetarian;

    @Column(name = "reserve_allergy_bowl", nullable = false)
    public boolean reserveAllergyBowl;

    @Column(name = "brings_guest", nullable = false)
    public boolean bringsGuest;

    @Column(name = "number_of_guest", nullable = false)
    public int numberOfGuest;

    @Column(name = "guest_wants_meat", nullable = false)
    public int guestWantsMeat;

    @Column(name = "guest_wants_vegetarian", nullable = false)
    public int guestWantsVegetarian;

    @Column(name = "guest_wants_allergy_bowl", nullable = false)
    public int guestWantsAllergyBowl;

    // If MealChoice references MealPlanUser by meal_plan_user_uuid, add a field like:
    // @ManyToOne
    // @JoinColumn(name = "meal_plan_user_uuid", referencedColumnName = "uuid", nullable = false)
    // public MealPlanUser mealPlanUser;
    // (Do this only if needed, based on your schema logic.)
}