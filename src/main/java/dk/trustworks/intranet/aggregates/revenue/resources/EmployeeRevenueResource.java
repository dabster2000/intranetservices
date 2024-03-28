package dk.trustworks.intranet.aggregates.revenue.resources;

import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "Registered Revenue")
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class EmployeeRevenueResource {

    @Inject
    RevenueService revenueService;

    // "/users/"+useruuid+"/revenue/registered/hours?month="+stringIt(month);

    @GET
    @Path("/{useruuid}/revenue/registered")
    public List<DateValueDTO> getRegisteredRevenueByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("periodFrom") String periodFrom, @QueryParam("periodTo") String periodTo) {
        return revenueService.getRegisteredRevenueByPeriodAndSingleConsultant(useruuid, periodFrom, periodTo);
    }

    @GET
    @Path("/{useruuid}/revenue/registered/hours")
    public List<DateValueDTO> getRegisteredHoursByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return revenueService.getRegisteredHoursPerSingleConsultantByPeriod(useruuid, DateUtils.dateIt(fromdate), DateUtils.dateIt(todate));
    }

    @GET
    @Path("/{useruuid}/revenue/registered/months/{month}")
    public GraphKeyValue getRegisteredRevenueForSingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        return new GraphKeyValue(useruuid, "Consultant revenue amount per month", revenueService.getRegisteredRevenueForSingleMonthAndSingleConsultant(useruuid, dateIt(month)));
    }

    @GET
    @Path("/{useruuid}/revenue/registered/months/{month}/hours")
    public GraphKeyValue getRegisteredHoursForSingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        return new GraphKeyValue(useruuid, "Consultant revenue per month", revenueService.getRegisteredHoursForSingleMonthAndSingleConsultant(useruuid, dateIt(month)));
    }
}