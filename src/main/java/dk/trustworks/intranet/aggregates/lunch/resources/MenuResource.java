package dk.trustworks.intranet.aggregates.lunch.resources;


import dk.trustworks.intranet.aggregates.lunch.services.MenuService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/mealplan/menu")
@RolesAllowed({"lunch:read"})
public class MenuResource {

    @Inject
    MenuService menuService;

    @GET
    @Path("/{weekNumber}")
    public byte[] getMenuForWeek(@PathParam("weekNumber") int weekNumber) {
        // Logic to fetch the menu as a PDF or any other format.
        return menuService.fetchMenuForWeek(weekNumber);
    }

    @POST
    @Path("/{weekNumber}")
    @Transactional
    @RolesAllowed({"lunch:write"})
    public String postMenuForWeek(@PathParam("weekNumber") int weekNumber, byte[] menuPdf) {
        // Logic to save the menu for the specified week.
        return menuService.saveMenuForWeek(weekNumber, menuPdf);
    }
}
