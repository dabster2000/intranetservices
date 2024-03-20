package dk.trustworks.intranet.aggregates.budgets.resources;

/*
@JBossLog
@Tag(name = "Budget")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@Path("/budgets")
public class BudgetResource {

    @Inject
    BudgetService budgetService;

    @GET
    @Path("/consultants/{consultantuuid}")
    public List<Budget> findByConsultantAndProject(@QueryParam("projectuuid") String projectuuid, @PathParam("consultantuuid") String consultantuuid) {
        return budgetService.findByConsultantAndProject(projectuuid, consultantuuid);
    }
}

 */