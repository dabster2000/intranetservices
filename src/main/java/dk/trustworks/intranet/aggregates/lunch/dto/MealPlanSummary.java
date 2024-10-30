package dk.trustworks.intranet.aggregates.lunch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class MealPlanSummary {

    public String mealPlanId;
    public List<DaySummary> mealPlanSummary;

}
