package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.finance.dto.TeamAbsenceOverviewDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCareerDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSalaryBandDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamTenureDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamTimeToFirstContractDTO;
import dk.trustworks.intranet.aggregates.finance.services.TeamDashboardService;
import dk.trustworks.intranet.aggregates.finance.services.TeamPeopleService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.userservice.services.TeamService;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "team")
@JBossLog
@Path("/teams")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"teams:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class TeamResource {

    @Inject
    TeamService teamService;

    @Inject
    TeamPeopleService teamPeopleService;

    @Inject
    TeamDashboardService teamDashboardService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public List<Team> listAll() {
        return teamService.listAll();
    }

    @GET
    @Path("/search/findByRoles")
    public List<Team> findByRoles(@QueryParam("useruuid") String useruuid, @QueryParam("date") String strDate, @QueryParam("roles") String roles) {
        return teamService.findByRoles(useruuid, dateIt(strDate), roles.split(","));
    }

    @GET
    @Path("/{teamuuid}/users")
    public List<User> getUsersByTeam(@PathParam("teamuuid") String teamuuid) {
        return getUsers(teamService.getUsersByTeam(teamuuid));
    }

    private static List<User> getUsers(List<User> usersByTeam) {
        usersByTeam.forEach(UserService::addChildrenToUser);
        return usersByTeam;
    }

    @GET
    @Path("/{teamuuid}/users/search/findByMonth")
    public List<User> getUsersByTeamAndMonth(@PathParam("teamuuid") String teamuuid, @QueryParam("month") String month) {
        return getUsers(teamService.getUsersByTeam(teamuuid, dateIt(month)));
    }

    @GET
    @Path("/{teamuuid}/users/search/findByMonthIncludingPreboarding")
    public List<User> getUsersByTeamAndMonthIncludingPreboarding(
            @PathParam("teamuuid") String teamuuid,
            @QueryParam("month") String month) {
        return getUsers(teamService.getUsersByTeamIncludingPreboarding(teamuuid, dateIt(month)));
    }

    @GET
    @Path("/{teamuuid}/users/search/findTeamleadersByMonth")
    public List<User> getTeamLeadersByTeam(@PathParam("teamuuid") String teamuuid, @QueryParam("month") String month) {
        return getUsers(teamService.getTeamLeadersByTeam(teamuuid, dateIt(month)));
    }

    @GET
    @Path("/{teamuuid}/users/search/findByFiscalYear")
    public List<User> getUsersByTeamAndFiscalYear(@PathParam("teamuuid") String teamuuid, @QueryParam("fiscalyear") String fiscalyear) {
        return getUsers(teamService.getUsersByTeamAndFiscalYear(teamuuid, Integer.parseInt(fiscalyear)));
    }

    @GET
    @Path("/teamleadbonustrue/users")
    public List<User> getTeammembersByTeamleadBonusEnabled() {
        return getUsers(teamService.getTeammembersByTeamleadBonusEnabled());
    }

    @GET
    @Path("/teamleadbonustrue/users/search/findByMonth")
    public List<User> getTeammembersByTeamleadBonusEnabledByMonth(@QueryParam("month") String month) {
        return getUsers(teamService.getTeammembersByTeamleadBonusEnabledByMonth(dateIt(month)));
    }

    @GET
    @Path("/teamleadbonusfalse/users")
    public List<User> getTeammembersByTeamleadBonusDisabled() {
        return getUsers(teamService.getTeammembersByTeamleadBonusDisabled());
    }

    @GET
    @Path("/teamleadbonusfalse/users/search/findByMonth")
    public List<User> getTeammembersByTeamleadBonusDisabledByMonth(@QueryParam("month") String month) {
        return getUsers(teamService.getTeammembersByTeamleadBonusDisabledByMonth(dateIt(month)));
    }

    @GET
    @Path("/owners")
    public List<User> getOwners() {
        return getUsers(teamService.getOwners());
    }

    @GET
    @Path("/owners/search/findByMonth")
    public List<User> getOwnersByMonth(@QueryParam("month") String month) {
        return getUsers(teamService.getOwnersByMonth(dateIt(month)));
    }

    @GET
    @Path("/teamops")
    public List<User> getTeamOps() {
        return getUsers(teamService.getTeamOps());
    }

    @GET
    @Path("/teamops/search/findByMonth")
    public List<User> getTeamOpsByMonth(@QueryParam("month") String month) {
        return getUsers(teamService.getTeamOpsByMonth(dateIt(month)));
    }

    @POST
    @Path("/{teamuuid}/users")
    @RolesAllowed({"teams:write"})
    public void addUserToTeam(@PathParam("teamuuid") String teamuuid, TeamRole teamrole) {
        teamService.addTeamroleToUser(teamuuid, teamrole);
    }

    @DELETE
    @Path("/{teamuuid}/users")
    @RolesAllowed({"teams:write"})
    public void deleteTeamRole(@PathParam("teamuuid") String teamuuid, @QueryParam("teamroleuuid") String teamroleuuid) {
        teamService.removeUserFromTeam(teamroleuuid);
    }

    @POST
    @Path("/regenerate-descriptions")
    @RolesAllowed({"teams:write"})
    public void regenerateDescriptions() {
        teamService.updateTeamDescription();
    }

    // -----------------------------------------------------------------------
    // Team Dashboard — People & Sick Leave endpoints
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamuuid}/dashboard/career-distribution")
    @RolesAllowed({"dashboard:read"})
    public List<TeamCareerDistributionDTO> getCareerDistribution(@PathParam("teamuuid") String teamuuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        return teamPeopleService.getCareerDistribution(teamuuid);
    }

    @GET
    @Path("/{teamuuid}/dashboard/tenure-distribution")
    @RolesAllowed({"dashboard:read"})
    public List<TeamTenureDistributionDTO> getTenureDistribution(@PathParam("teamuuid") String teamuuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        return teamPeopleService.getTenureDistribution(teamuuid);
    }

    @GET
    @Path("/{teamuuid}/dashboard/absence-overview")
    @RolesAllowed({"dashboard:read"})
    public List<TeamAbsenceOverviewDTO> getAbsenceOverview(@PathParam("teamuuid") String teamuuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        return teamPeopleService.getAbsenceOverview(teamuuid);
    }

    @GET
    @Path("/{teamuuid}/dashboard/time-to-first-contract")
    @RolesAllowed({"dashboard:read"})
    public TeamTimeToFirstContractDTO getTimeToFirstContract(@PathParam("teamuuid") String teamuuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        return teamPeopleService.getTimeToFirstContract(teamuuid);
    }

    @GET
    @Path("/{teamuuid}/dashboard/sick-leave-tracking")
    @RolesAllowed({"dashboard:read"})
    public List<TeamSickLeaveTrackingDTO> getSickLeaveTracking(@PathParam("teamuuid") String teamuuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        return teamPeopleService.getSickLeaveTracking(teamuuid);
    }

    @GET
    @Path("/{teamuuid}/dashboard/salary-band-positioning")
    @RolesAllowed({"dashboard:read"})
    public List<TeamSalaryBandDTO> getSalaryBandPositioning(@PathParam("teamuuid") String teamuuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        return teamPeopleService.getSalaryBandPositioning(teamuuid);
    }
}
