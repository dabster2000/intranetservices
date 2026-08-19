package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.finance.dto.AllPracticesCareerDistributionResult;
import dk.trustworks.intranet.aggregates.finance.dto.ConsultantAbsenceDayDTO;
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
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.model.TeamSetting;
import dk.trustworks.intranet.services.PracticeService;
import dk.trustworks.intranet.services.TeamSettingService;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.ScopeResolution;
import dk.trustworks.intranet.userservice.services.TeamLogoService;
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
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Team API. Reads require {@code teams:read}; mutations additionally require
 * {@code teams:write}. Mutation bodies are record DTOs so no entity is
 * mass-assigned, and validation is hand-rolled ({@link BadRequestException} →
 * 400 via the exception mapper) rather than Bean Validation.
 * <p>
 * <b>There is deliberately no delete or archive endpoint.</b> Teams are
 * referenced by {@code teamroles} (no FK — rows would silently dangle),
 * {@code team_practice_assignment}, {@code team_settings} and the bonus
 * configuration, and nothing in the model carries an {@code active} flag to
 * filter on. Retiring a team is a data-migration decision, not a button.
 */
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
    PracticeService practiceService;

    @Inject
    TeamSettingService teamSettingService;

    @Inject
    TeamLogoService teamLogoService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    ScopeGuard scope;

    static final String TEAMS_WRITE = "teams:write";

    /**
     * Phase 10.2 (access-intent Decision 10): membership writes are the one
     * team mutation a <em>bounded</em> {@code teams:write} holder (a future
     * TEAMLEAD@TEAM grant) may perform — and only on a team they currently
     * lead. The self-widening this permits (adding a member is the act of
     * granting yourself visibility of that member) is the owner's deliberate
     * decision, not an accident. Unbounded holders (HR, ADMIN — today's only
     * write audience) and headerless callers are untouched; an actor with no
     * {@code teams:write} grant at all is refused.
     */
    private void requireMembershipWriteReach(String teamuuid) {
        ScopeResolution reach = scope.reachOrNull(TEAMS_WRITE);
        if (reach == null || reach.unbounded()) {
            return;
        }
        if (reach.isNone()) {
            throw new ForbiddenException("Team membership changes outside your reach");
        }
        boolean leadsTeam = teamService.getTeamLeadersByTeam(teamuuid, java.time.LocalDate.now())
                .stream().anyMatch(u -> u != null && scope.actorOrNull().equals(u.getUuid()));
        if (!leadsTeam) {
            log.infof("teams:write scope denied — actor %s does not lead team %s",
                    scope.actorOrNull(), teamuuid);
            throw new ForbiddenException("Team membership changes outside your reach");
        }
    }

    /**
     * Team-<em>identity</em> writes (today: the logo) are reachable by an
     * unbounded {@code teams:write} holder (HR, ADMIN) or by a LEADER
     * <em>or SPONSOR</em> of that one team — the same pair
     * {@code TeamDashboardService.validateTeamAccess} already admits to the
     * team dashboard, which is where the control lives. Gating the write on a
     * narrower audience than the page it sits on would render a button that
     * always 403s.
     * <p>
     * Deliberately wider than {@link #requireMembershipWriteReach}, which
     * accepts LEADER only: moving people between teams is a membership change
     * with bonus and visibility consequences, whereas the logo is presentation
     * and reverting it costs one upload.
     */
    private void requireTeamIdentityWriteReach(String teamuuid) {
        ScopeResolution reach = scope.reachOrNull(TEAMS_WRITE);
        if (reach == null || reach.unbounded()) {
            return;
        }
        if (reach.isNone() || !teamService.isLeaderOrSponsor(teamuuid, scope.actorOrNull(), LocalDate.now())) {
            log.infof("teams:write scope denied — actor %s neither leads nor sponsors team %s",
                    scope.actorOrNull(), teamuuid);
            throw new ForbiddenException("Team logo changes outside your reach");
        }
    }

    public record CreateTeamRequest(String name, String shortname, String description, String practiceCode) {
    }

    /**
     * @param filename   advisory; sanitized before it is stored
     * @param fileBase64 the image bytes, base64-encoded. JSON rather than
     *                   multipart because every other photo write in this
     *                   backend takes that shape, and the BFF is the only
     *                   caller — it receives the multipart upload and re-encodes.
     */
    public record UpdateTeamLogoRequest(String filename, String fileBase64) {
    }

    public record UpdateTeamRequest(String name, String shortname, String description) {
    }

    public record UpdateTeamPracticeRequest(String practiceCode, String practiceUuid) {
    }

    public record UpdateTeamSettingRequest(String value) {
    }

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

    /**
     * Distinct users who are LEADER of ANY team today — the interviewer
     * picker's "Team leaders" grouping. Deliberately shallow (no
     * {@code addChildrenToUser}): callers only need identity fields, and
     * the scope response filter strips the sensitive rest anyway.
     */
    @GET
    @Path("/leaders")
    public List<User> getAllActiveTeamLeaders() {
        return teamService.getAllActiveTeamLeaders(LocalDate.now());
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
        requireMembershipWriteReach(teamuuid);
        teamService.addTeamroleToUser(teamuuid, teamrole);
    }

    @DELETE
    @Path("/{teamuuid}/users")
    @RolesAllowed({"teams:write"})
    public void deleteTeamRole(@PathParam("teamuuid") String teamuuid, @QueryParam("teamroleuuid") String teamroleuuid) {
        // The row mutated is the teamrole's own team — check that, not the path
        // segment, so a bounded lead cannot delete another team's row by
        // addressing it under their own team's URL.
        TeamRole row = findTeamRole(teamroleuuid);
        requireMembershipWriteReach(row != null ? row.getTeamuuid() : teamuuid);
        teamService.removeUserFromTeam(teamroleuuid);
    }

    /** Package-private seam so the scope tests can stub the row lookup without Panache. */
    TeamRole findTeamRole(String teamroleuuid) {
        return TeamRole.findById(teamroleuuid);
    }

    @POST
    @Path("/regenerate-descriptions")
    @RolesAllowed({"teams:write"})
    @ScopeEnforced
    public void regenerateDescriptions() {
        teamService.updateTeamDescription();
    }

    // -----------------------------------------------------------------------
    // Team administration (settings-teams tab, V430)
    // -----------------------------------------------------------------------

    /**
     * Flattens CR/LF out of a free-text value bound for the log. Without this a
     * caller-supplied newline splits one log line into two, and the awslogs
     * driver cannot tell the forged half from a genuine event.
     */
    private static String logSafe(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n]+", " ");
    }

    @POST
    @ScopeEnforced
    @RolesAllowed({"teams:write"})
    public Response createTeam(CreateTeamRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        practiceService.validateTeamPracticeCode(request.practiceCode());
        Team created = teamService.createTeam(request.name(), request.shortname(),
                request.description(), request.practiceCode());
        // Logged after the write, from the persisted row: these updatedBy= lines are
        // the audit trail for team mutations, so they must not carry unvalidated
        // free text. logSafe strips CR/LF — a newline in a request field would
        // otherwise render as a second, forged log event under the awslogs driver.
        log.infof("TeamResource.createTeam teamuuid=%s name=%s shortname=%s practiceCode=%s updatedBy=%s",
                created.getUuid(), logSafe(created.getName()), logSafe(created.getShortname()),
                logSafe(request.practiceCode()), requestHeaderHolder.getUserUuid());
        return Response.created(URI.create("/teams/" + created.getUuid())).entity(created).build();
    }

    /** Partial edit — omitted fields are left alone. The practice link has its own endpoint. */
    @PUT
    @Path("/{teamuuid}")
    @ScopeEnforced
    @RolesAllowed({"teams:write"})
    public Team updateTeam(@PathParam("teamuuid") String teamuuid, UpdateTeamRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        log.infof("TeamResource.updateTeam teamuuid=%s updatedBy=%s", teamuuid, requestHeaderHolder.getUserUuid());
        return teamService.updateTeam(teamuuid, request.name(), request.shortname(), request.description());
    }

    /**
     * Replaces the team's logo — the image {@code /organization} and the team
     * dashboard render for this team.
     * <p>
     * A team-scoped endpoint rather than a second caller of
     * {@code PUT /files/photos/logo}: that one is gated on
     * {@code documents:write}, which says nothing about <em>which</em> team's
     * identity the caller may change, and its {@code relateduuid} is whatever
     * the body claims. Here the team is a path segment, so
     * {@link #requireTeamIdentityWriteReach} can bind the write to a team the
     * acting human actually leads or sponsors.
     * <p>
     * No {@code @ScopeEnforced}: that annotation demands an ALL-scope grant,
     * which would lock out the TEAM-scoped {@code teams:write} TEAMLEAD holds
     * (V473) — the audience this endpoint exists for.
     */
    @PUT
    @Path("/{teamuuid}/logo")
    @RolesAllowed({"teams:write"})
    public Response updateTeamLogo(@PathParam("teamuuid") String teamuuid, UpdateTeamLogoRequest request) {
        if (request == null || request.fileBase64() == null || request.fileBase64().isBlank()) {
            throw new BadRequestException("fileBase64 is required");
        }
        requireTeamIdentityWriteReach(teamuuid);
        teamLogoService.updateTeamLogo(teamuuid, request.fileBase64(), request.filename());
        // Audit line for a team mutation — logSafe strips CR/LF so a newline in
        // the client-supplied filename cannot forge a second log event.
        log.infof("TeamResource.updateTeamLogo teamuuid=%s filename=%s updatedBy=%s",
                teamuuid, logSafe(request.filename()), requestHeaderHolder.getUserUuid());
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Team → practice link and team-scoped settings (V418)
    // -----------------------------------------------------------------------

    @PUT
    @Path("/{teamuuid}/practice")
    @ScopeEnforced
    @RolesAllowed({"teams:write"})
    public Team updateTeamPractice(@PathParam("teamuuid") String teamuuid, UpdateTeamPracticeRequest body) {
        String practiceCode = resolvePracticeCode(body);
        practiceService.validateTeamPracticeCode(practiceCode);
        log.infof("TeamResource.updateTeamPractice teamuuid=%s practiceCode=%s updatedBy=%s",
                teamuuid, practiceCode, requestHeaderHolder.getUserUuid());
        return teamService.updateTeamPractice(teamuuid, practiceCode);
    }

    /**
     * The practice preview a client must show before {@link #updateTeamPractice}:
     * the members that write would actually re-derive. Deliberately NOT
     * {@code GET /teams/{uuid}/users} — that one has no temporal predicate and
     * over-reports; {@code .../users/search/findByMonth} adds status and
     * consultant-type filters the cascade does not apply and under-reports.
     */
    @GET
    @Path("/{teamuuid}/practice/impact")
    public TeamService.TeamPracticeImpact getTeamPracticeImpact(@PathParam("teamuuid") String teamuuid) {
        return teamService.getTeamPracticeImpact(teamuuid);
    }

    /**
     * Cascade-set sizes for every team in one query, keyed by team uuid — what the
     * teams grid shows in its members column. Asking {@code /practice/impact} per
     * row instead costs a request (and a full caller-role resolution) per team.
     * <p>
     * The literal segment cannot collide with {@code /{teamuuid}/...}: JAX-RS
     * prefers literals, and no team uuid is the string "member-counts".
     */
    @GET
    @Path("/member-counts")
    public Map<String, Long> getTeamMemberCounts() {
        return teamService.getTeamMemberCounts();
    }

    /**
     * {@code practiceUuid} is the canonical identifier (§4.5) and wins when
     * supplied; {@code practiceCode} is the pre-existing alias and still works.
     * Both absent clears the team's practice.
     */
    private String resolvePracticeCode(UpdateTeamPracticeRequest body) {
        if (body == null) return null;
        if (body.practiceUuid() == null || body.practiceUuid().isBlank()) return body.practiceCode();
        Practice practice = practiceService.resolveByIdOrCode(body.practiceUuid());
        if (practice == null) throw new BadRequestException("Practice not found: " + body.practiceUuid());
        return practice.getCode();
    }

    @GET
    @Path("/settings")
    public List<TeamSetting> getTeamSettings(@QueryParam("key") String key) {
        if (key == null || key.isBlank()) throw new BadRequestException("Query parameter 'key' is required");
        return teamSettingService.findByKey(key);
    }

    @PUT
    @Path("/{teamuuid}/settings/{key}")
    @ScopeEnforced
    @RolesAllowed({"teams:write"})
    public TeamSetting updateTeamSetting(@PathParam("teamuuid") String teamuuid,
                                         @PathParam("key") String key,
                                         UpdateTeamSettingRequest body) {
        if (body == null || body.value() == null) throw new BadRequestException("value is required");
        String updatedBy = requestHeaderHolder.getUserUuid();
        log.infof("TeamResource.updateTeamSetting teamuuid=%s key=%s updatedBy=%s", teamuuid, key, updatedBy);
        return teamSettingService.saveSetting(teamuuid, key, body.value(), updatedBy);
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
    @Path("/all-practices/career-distribution")
    @RolesAllowed({"dashboard:read"})
    public AllPracticesCareerDistributionResult getAllPracticesCareerDistribution() {
        return teamPeopleService.getAllPracticesCareerDistribution();
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
    public jakarta.ws.rs.core.Response getAbsenceOverview(
            @PathParam("teamuuid") String teamuuid,
            @QueryParam("consultantUuid") String consultantUuid) {
        teamDashboardService.validateTeamAccess(teamuuid, requestHeaderHolder.getUserUuid());
        if (consultantUuid != null && !consultantUuid.isBlank()) {
            List<ConsultantAbsenceDayDTO> result = teamPeopleService.getConsultantAbsenceOverview(teamuuid, consultantUuid);
            return jakarta.ws.rs.core.Response.ok(result).build();
        }
        List<TeamAbsenceOverviewDTO> result = teamPeopleService.getAbsenceOverview(teamuuid);
        return jakarta.ws.rs.core.Response.ok(result).build();
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
