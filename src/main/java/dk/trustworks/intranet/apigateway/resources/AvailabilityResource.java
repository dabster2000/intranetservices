package dk.trustworks.intranet.apigateway.resources;

/*
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

    /*
    @GET
    @Path("/booking")
    public List<UserBooking> getUserBooking(@QueryParam("monthsinpast") int monthsInPast, @QueryParam("monthsinfuture") int monthsInFuture) {
        return availabilityService.getUserBooking(monthsInPast, monthsInFuture);
    }

}
     */
