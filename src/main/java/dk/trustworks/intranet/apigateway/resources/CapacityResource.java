package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.dto.Capacity;
import dk.trustworks.intranet.userservice.services.CapacityService;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "Capacity")
@Path("/capacities")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"capacity:read"})
@SecurityRequirement(name = "jwt")
public class CapacityResource {

    @Inject
    CapacityService capacityService;

    @Inject
    dk.trustworks.intranet.aggregates.finance.services.JuniorTalentService juniorTalentService;

    /**
     * JK Team 2.0 WP7 (spec §4.7): Junior Talent — every active STUDENT with declared
     * availability over the next weeks, faglighed, tags, graduation and the step-up
     * watchlist. Read-only; same {@code capacity:read} audience as Staffing. Excludes the
     * declaration note and the free-text education field by construction (§4.7.3).
     */
    @GET
    @Path("/junior-talent")
    public dk.trustworks.intranet.aggregates.finance.dto.JuniorTalentDTO juniorTalent(
            @QueryParam("weeks") @DefaultValue("4") int weeks) {
        return juniorTalentService.build(LocalDate.now(), weeks);
    }

    @GET
    public List<Capacity> calculateCapacityByPeriod(@QueryParam("fromdate") Optional<String> fromDateString, @QueryParam("todate") Optional<String> toDateString) {
        return capacityService.calculateCapacityByPeriod(
                dateIt(fromDateString.orElse("2014-01-01")),
                dateIt(toDateString.orElse(stringIt(LocalDate.now()))));
    }

    @GET
    @Path("/{statusdate}")
    public List<Capacity> calculateCapacityByMonthByUser(@QueryParam("useruuid") Optional<String> useruuid, @PathParam("statusdate") String statusdate) {
        return useruuid.map(s -> Collections.singletonList(capacityService.calculateCapacityByMonthByUser(s, dateIt(statusdate))))
                .orElseGet(() -> capacityService.calculateCapacityByMonth(dateIt(statusdate)));
    }
}