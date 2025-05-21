package dk.trustworks.intranet.aggregates.lunch.services;


import dk.trustworks.intranet.aggregates.lunch.dto.DaySummary;
import dk.trustworks.intranet.aggregates.lunch.dto.MealPlanSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SummaryService {
    @Inject
    EntityManager entityManager;

    public MealPlanSummary fetchSummaryForMealPlan(String mealPlanId) {
        // SQL query to aggregate meal choices and buffer data for the given meal plan.
        String sql = """
        
                SELECT
          mc.weekday,
          SUM(CASE WHEN mc.selected_meal_type = 'meat' THEN 1 ELSE 0 END) AS total_meat,
          SUM(CASE WHEN mc.selected_meal_type = 'vegetarian' THEN 1 ELSE 0 END) AS total_vegetarian,
          SUM(CASE WHEN mc.selected_meal_type = 'allergyBowl' THEN 1 ELSE 0 END) AS total_allergy_bowl,
          SUM(cast(mc.reserve_meat as signed)) AS reserved_meat,
          SUM(cast(mc.reserve_vegetarian as signed)) AS reserved_vegetarian,
          SUM(cast(mc.reserve_allergy_bowl as signed)) AS reserved_allergy_bowl,
          SUM(cast(mc.wants_breakfast as signed) AS wants_breakfast,
          SUM(mc.guest_wants_meat) AS guest_meat,
          SUM(mc.guest_wants_vegetarian) AS guest_vegetarian,
          SUM(mc.guest_wants_allergy_bowl) AS guest_allergy_bowl,
          b.buffer_meat,
          b.buffer_vegetarian,
          b.buffer_allergy_bowl
        FROM
        mealplan mp
        Join
        mealplanuser mpu ON mpu.mealplanuuid = mp.uuid
        left join
        mealchoice mc ON mpu.uuid = mc.mealplanuser_uuid
        JOIN
        mealplanbuffer mpb ON mpb.mealPlan_uuid = mp.uuid
        left join
        buffer b ON b.mealplanbuffer_id = mpb.uuid
        WHere mp.id = 'mealplan456'
        GROUP BY
          mc.weekday, b.buffer_meat, b.buffer_vegetarian, b.buffer_allergy_bowl
        ORDER BY
          mc.weekday;
        """;

        // Execute the query
        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        query.setParameter("mealPlanId", mealPlanId);
        List<Tuple> resultList = query.getResultList();

        // Parse the results into a MealPlanSummary object
        return parseSummary(resultList, mealPlanId);
    }


    private MealPlanSummary parseSummary(List<Tuple> resultList, String mealPlanId) {
        List<DaySummary> daySummaries = new ArrayList<>();

        // Iterate through the results from the query and create DaySummary objects
        for (Tuple tuple : resultList) {
            String weekday = tuple.get("weekday", String.class);
            int totalMeat = tuple.get("total_meat", Integer.class);
            int totalVegetarian = tuple.get("total_vegetarian", Integer.class);
            int totalAllergyBowl = tuple.get("total_allergy_bowl", Integer.class);
            int reservedMeat = tuple.get("reserved_meat", Integer.class);
            int reservedVegetarian = tuple.get("reserved_vegetarian", Integer.class);
            int reservedAllergyBowl = tuple.get("reserved_allergy_bowl", Integer.class);
            int totalBreakfast = tuple.get("total_breakfast", Integer.class);
            int guestMeat = tuple.get("guest_meat", Integer.class);
            int guestVegetarian = tuple.get("guest_vegetarian", Integer.class);
            int guestAllergyBowl = tuple.get("guest_allergy_bowl", Integer.class);
            int bufferMeat = tuple.get("buffer_meat") != null ? tuple.get("buffer_meat", Integer.class) : 0;
            int bufferVegetarian = tuple.get("buffer_vegetarian") != null ? tuple.get("buffer_vegetarian", Integer.class) : 0;
            int bufferAllergyBowl = tuple.get("buffer_allergy_bowl") != null ? tuple.get("buffer_allergy_bowl", Integer.class) : 0;

            // Create a DaySummary object with the aggregated data
            DaySummary daySummary = new DaySummary(weekday, totalMeat, totalVegetarian, totalAllergyBowl,
                    reservedMeat, reservedVegetarian, reservedAllergyBowl,
                    totalBreakfast, guestMeat, guestVegetarian, guestAllergyBowl,
                    bufferMeat, bufferVegetarian, bufferAllergyBowl);
            daySummaries.add(daySummary);
        }

        // Return the final MealPlanSummary
        return new MealPlanSummary(mealPlanId, daySummaries);
    }

}
