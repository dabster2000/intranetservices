package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.commands.AggregateCommand;
import dk.trustworks.intranet.aggregates.users.events.CreateSalaryEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteSalaryEvent;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.Team;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.RoleType;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.validation.Valid;
import javax.ws.rs.*;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static dk.trustworks.intranet.userservice.model.enums.TeamMemberType.LEADER;
import static dk.trustworks.intranet.userservice.model.enums.TeamMemberType.SPONSOR;

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
    AggregateCommand aggregateCommand;

    @Inject
    TeamService teamService;

    @Inject
    UserService userService;

    @GET
    @Path("/{useruuid}/salaries")
    public List<Salary> listAll(@PathParam("useruuid") String useruuid) {
        List<RoleType> roles = jwt.getGroups().stream().map(RoleType::valueOf).toList();

        // Check if this is only a user and then if the user requests own salary
        if(roles.stream().noneMatch(roleType -> roleType.equals(RoleType.ADMIN) || roleType.equals(RoleType.CXO) || roleType.equals(RoleType.TEAMLEAD))) {
            if(!username.equals(userService.findById(useruuid, true).getUsername())) return Collections.emptyList();
            return salaryService.listAll(useruuid);
        }

        if(roles.stream().anyMatch(roleType -> roleType.equals(RoleType.ADMIN) || roleType.equals(RoleType.CXO) || roleType.equals(RoleType.SYSTEM))) return salaryService.listAll(useruuid);

        LocalDate date = LocalDate.now().withDayOfMonth(1);
        User leader = userService.findByUsername(username, true);
        for (Team team : teamService.findByRoles(leader.getUuid(), date, LEADER.name(), SPONSOR.name())) {
            if(teamService.getUsersByTeam(team.getUuid(), date).stream().anyMatch(user -> user.getUuid().equals(useruuid))) return salaryService.listAll(useruuid);
        }
        return Collections.singletonList(new Salary(LocalDate.now().withDayOfMonth(1), 0, useruuid));
    }

    @POST
    @Path("/{useruuid}/salaries")
    public void create(@PathParam("useruuid") String useruuid, @Valid Salary salary) {
        salary.setUseruuid(useruuid);
        CreateSalaryEvent createSalaryEvent = new CreateSalaryEvent(useruuid, salary);
        aggregateCommand.handleEvent(createSalaryEvent);
        //salaryService.create(salary);
    }

    @DELETE
    @Path("/{useruuid}/salaries/{salaryuuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("salaryuuid") String salaryuuid) {
        DeleteSalaryEvent deleteSalaryEvent = new DeleteSalaryEvent(useruuid, salaryuuid);
        aggregateCommand.handleEvent(deleteSalaryEvent);
        //salaryService.delete(useruuid, salaryuuid);
    }
}