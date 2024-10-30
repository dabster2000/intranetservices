package dk.trustworks.intranet.aggregates.lunch.resources;

import dk.trustworks.intranet.aggregates.lunch.model.MealBuffer;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/lunch/buffers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MealBufferResource {

    @GET
    @Path("/{mealPlanId}")
    public List<MealBuffer> getBuffersForMealPlan(@PathParam("mealPlanId") String mealPlanId) {
        return MealBuffer.find("meal_plan_id", mealPlanId).list();
    }

    @POST
    @Transactional
    public MealBuffer createBuffer(MealBuffer buffer) {
        buffer.persist();
        return buffer;
    }

}
