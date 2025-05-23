package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@Tag(name = "User Availabilities")
@JBossLog
@Path("/users")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserAvailabilityResource {

    @Inject
    AvailabilityService availabilityService;

    @GET
    @Path("/availabilities")
    public List<EmployeeAvailabilityPerMonth> getAllUserAvailabilitiesByPeriod(@QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getAllEmployeeAvailabilityByPeriod(dateIt(periodFrom), dateIt(periodTo));
    }

    @GET
    @Path("/{useruuid}/availabilities")
    public List<EmployeeAvailabilityPerMonth> getAvailabilitiesByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getEmployeeDataPerMonth(useruuid, dateIt(periodFrom), dateIt(periodTo));
    }

    @GET
    @Path("/{useruuid}/availabilities/days")
    public List<BiDataPerDay> getBudgetsBySingleDayAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getEmployeeDataPerDay(useruuid, dateIt(periodFrom), dateIt(periodTo));
    }
}
