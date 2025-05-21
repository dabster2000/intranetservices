package dk.trustworks.intranet.aggregates.lunch.resources;

import dk.trustworks.intranet.aggregates.lunch.dto.MealPlanSummary;
import dk.trustworks.intranet.aggregates.lunch.model.MealPlan;
import dk.trustworks.intranet.aggregates.lunch.services.SummaryService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.util.List;

@Path("/lunch/mealplans")
public class MealPlanResource {

    @Inject
    SummaryService summaryService;

    @GET
    @Path("/open")
    public List<MealPlan> getOpenMealPlans() {
        return MealPlan.find("status", "OPEN").list();
    }

    @GET
    @Path("/closed")
    public List<MealPlan> getClosedMealPlans() {
        return MealPlan.find("status", "CLOSED").list();
    }

    @POST
    @Transactional
    public MealPlan createMealPlan(MealPlan mealPlan) {
        mealPlan.persist();
        return mealPlan;
    }

    @GET
    @Path("/summary/{mealPlanId}")
    public MealPlanSummary getMealPlanSummary(@PathParam("mealPlanId") String mealPlanId) {
        return summaryService.fetchSummaryForMealPlan(mealPlanId);
    }
}
