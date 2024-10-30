package dk.trustworks.intranet.aggregates.lunch.resources;

import dk.trustworks.intranet.aggregates.lunch.model.MealChoice;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/lunch/mealchoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MealChoiceResource {

    @GET
    @Path("/user/{mealPlanId}")
    public List<MealChoice> getUserChoices(@PathParam("mealPlanId") String mealPlanId, @QueryParam("userId") String userId) {
        return MealChoice.find("mealPlanUser.mealPlan.id = ?1 and mealPlanUser.userId = ?2", mealPlanId, userId).list();
    }

    @POST
    @Path("/updateChoices")
    @Transactional
    public MealChoice updateMealChoice(MealChoice mealChoice) {
        mealChoice.persist();
        return mealChoice;
    }
}
