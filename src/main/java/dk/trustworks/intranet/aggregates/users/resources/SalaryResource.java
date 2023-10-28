package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateSalaryEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteSalaryEvent;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.services.TeamService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@JBossLog
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class SalaryResource {

    @Inject
    @Claim(standard = Claims.preferred_username)
    String username;

    @Inject
    @Claim(standard = Claims.groups)
    String groups;

    @Inject
    JsonWebToken jwt;

    @Inject
    SalaryService salaryService;

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    TeamService teamService;

    @Inject
    UserService userService;

    @GET
    @Path("/{useruuid}/salaries")
    public List<Salary> listAll(@PathParam("useruuid") String useruuid) {
        return salaryService.findByUseruuid(useruuid);
    }

    @POST
    @Path("/{useruuid}/salaries")
    public void create(@PathParam("useruuid") String useruuid, Salary salary) {
        salary.setUseruuid(useruuid);
        CreateSalaryEvent createSalaryEvent = new CreateSalaryEvent(useruuid, salary);
        aggregateEventSender.handleEvent(createSalaryEvent);
    }

    @DELETE
    @Path("/{useruuid}/salaries/{salaryuuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("salaryuuid") String salaryuuid) {
        DeleteSalaryEvent deleteSalaryEvent = new DeleteSalaryEvent(useruuid, salaryuuid);
        aggregateEventSender.handleEvent(deleteSalaryEvent);
    }
}