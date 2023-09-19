package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.AvailabilityService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.UserBooking;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "Availability")
@Path("/cached/availabilities")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class AvailabilityResource {

    @Inject
    WorkService workService;

    @Inject
    AvailabilityService availabilityService;

    @GET
    public List<AvailabilityDocument> getAvailabilityDocumentsByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return availabilityService.getAvailabilityDocumentsByPeriod(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/months/{datemonth}/users/{useruuid}")
    public AvailabilityDocument getConsultantAvailabilityByMonth(@PathParam("useruuid") String useruuid, @PathParam("datemonth") String datemonth) {
        return availabilityService.getConsultantAvailabilityByMonth(useruuid, dateIt(datemonth));
    }

    @GET
    @Path("/months/{datemonth}/users/{useruuid}/workdays")
    public GraphKeyValue getWorkDaysInMonth(@PathParam("useruuid") String useruuid, @PathParam("datemonth") String datemonth) {
        return new GraphKeyValue(UUID.randomUUID().toString(), "Number of available workdays in month "+datemonth, workService.getWorkDaysInMonth(useruuid, dateIt(datemonth)));
    }

    @GET
    @Path("/months/{datemonth}/users/count")
    public GraphKeyValue countActiveConsultantsByMonth(@PathParam("datemonth") String datemonth, @QueryParam("statustype") StatusType statusType, @QueryParam("consultanttype") ConsultantType consultantType) {
        return new GraphKeyValue(UUID.randomUUID().toString(), "Number of users in month "+datemonth, availabilityService.countUsersByMonthAndStatusTypeAndConsultantType(dateIt(datemonth), statusType, consultantType));
    }

    @GET
    @Path("/booking")
    public List<UserBooking> getUserBooking(@QueryParam("monthsinpast") int monthsInPast, @QueryParam("monthsinfuture") int monthsInFuture) {
        return availabilityService.getUserBooking(monthsInPast, monthsInFuture);
    }
}