package dk.trustworks.intranet.apigateway.resources;

/*
@Tag(name = "expense")
@JBossLog
@RequestScoped
@Path("/expenses")
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"USER"})
@SecurityRequirement(name = "jwt")
public class ExpenseOldResource {

    @Inject
    @RestClient
    ExpenseAPI expenseAPI;

    @GET
    @Path("/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        return expenseAPI.findByUuid(uuid);
    }

    @GET
    @Path("/file/{uuid}")
    public ExpenseFile getFileById(@PathParam("uuid") String uuid) {
        return expenseAPI.getFileById(uuid);
    }

    @GET
    @Path("/user/{useruuid}")
    public List<Expense> findByUser(@PathParam("useruuid") String useruuid, @QueryParam("limit") Optional<String> limit, @QueryParam("page") Optional<String> page) {
        if(limit.isPresent() && page.isPresent())
            return expenseAPI.findByUser(useruuid, limit, page);
        else
            return expenseAPI.findByUser(useruuid);
    }

    @GET
    @Path("/project/{projectuuid}/search/period")
    public List<Expense> findByProjectAndPeriod(@PathParam("projectuuid") String projectuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByProjectAndPeriod(projectuuid, fromdate, todate);
    }

    @GET
    @Path("/user/{useruuid}/search/period")
    public List<Expense> findByUserAndPeriod(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByUserAndPeriod(useruuid, fromdate, todate);
    }

    @GET
    @Path("/search/period")
    public List<Expense> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return expenseAPI.findByPeriod(fromdate, todate);
    }

    @POST
    public void save(Expense expense) {
        if(expense.getUseruuid().equals("173ee0b6-4ee5-11e7-b114-b2f933d5fe66")) return;
        expenseAPI.save(expense);
    }

    @PUT
    @Path("/{uuid}")
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        expenseAPI.updateOne(uuid, expense);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        expenseAPI.delete(uuid);
    }

}
*/
