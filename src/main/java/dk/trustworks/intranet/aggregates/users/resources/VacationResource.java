package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.VacationService;
import dk.trustworks.intranet.dto.DateValueDTO;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;

@Path("/users")
public class VacationResource {

    @Inject
    VacationService vacationService;

    @GET
    @Path("/{useruuid}/vacation")
    public Response getVacationOverview(@PathParam("useruuid") String useruuid, @QueryParam("year") int year) {
        try {
            DateValueDTO overview = new DateValueDTO(LocalDate.of(year, 1, 1), vacationService.calculateRemainingVacationDays(useruuid, year));
            return Response.ok(overview).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/{useruuid}/vacation/transfer")
    public Response transferVacationDays(@PathParam("useruuid") String useruuid, @QueryParam("year") int year, @QueryParam("days") double days) {
        try {
            vacationService.transferVacationDays(useruuid, year, days);
            return Response.ok("Vacation days transferred successfully.").build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }
}