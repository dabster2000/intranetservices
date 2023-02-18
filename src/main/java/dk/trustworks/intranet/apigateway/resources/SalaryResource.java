package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.Team;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.RoleType;
import dk.trustworks.intranet.userservice.services.SalaryService;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.userservice.services.UserService;
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
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@JBossLog
@RolesAllowed({"CXO", "ADMIN"})
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
public class SalaryResource {

    @Inject
    @Claim(standard = Claims.preferred_username)
    String username;

    @Inject
    JsonWebToken jwt;

    @Inject
    SalaryService salaryService;

    @Inject
    TeamService teamService;

    @Inject
    UserService userService;

    @GET
    @Path("/{useruuid}/salaries")
    @RolesAllowed({"TEAMLEAD", "CXO", "ADMIN"})
    public List<Salary> listAll(@PathParam("useruuid") String useruuid) {
        List<RoleType> roles = jwt.getGroups().stream().map(RoleType::valueOf).toList();
        if(roles.stream().anyMatch(roleType -> roleType.equals(RoleType.ADMIN) || roleType.equals(RoleType.CXO))) return salaryService.listAll(useruuid);

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
        salaryService.create(useruuid, salary);
    }

    @DELETE
    @Path("/{useruuid}/salaries/{salaryuuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("salaryuuid") String salaryuuid) {
        salaryService.delete(useruuid, salaryuuid);
    }
}