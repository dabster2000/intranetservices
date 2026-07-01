package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.AllTeamsBonusRankingDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamBonusProjectionDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamBonusProjectionService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.ScopeContext;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import dk.trustworks.intranet.userservice.services.TeamService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.*;

/**
 * Aggregate bonus operations and reporting.
 *
 * Path kept as /invoices/bonuses for compatibility with existing clients.
 */
@Tag(name = "invoice-bonus-aggregates", description = "Aggregate bonus operations and reporting")
@Path("/invoices/bonuses")
@RequestScoped
@SecurityRequirement(name = "jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"partnerbonus:read"})
public class BonusAggregateResource {

    @Inject
    TeamBonusProjectionService teamBonusProjectionService;

    @Inject
    TeamService teamService;

    @Inject
    UserService userService;

    @Inject
    ScopeContext scopeContext;

    /** DTO for eligible team leader information. */
    public record EligibleLeader(
            @Schema(description = "User UUID") String userUuid,
            @Schema(description = "User full name") String userName,
            @Schema(description = "Team UUID") String teamUuid,
            @Schema(description = "Team name") String teamName,
            @Schema(description = "Effective start date within fiscal year") LocalDate startDate,
            @Schema(description = "Effective end date within fiscal year") LocalDate endDate
    ) {}

    /** Response DTO for eligible team leaders. */
    public record EligibleLeadersResponse(
            @Schema(description = "Financial year (starting year)") int financialYear,
            @Schema(description = "Period start (inclusive)") LocalDate periodStart,
            @Schema(description = "Period end (inclusive)") LocalDate periodEnd,
            @Schema(description = "List of eligible team leaders") List<EligibleLeader> eligibleLeaders,
            @Schema(description = "Total number of eligible leaders") int totalLeaders
    ) {}

    @GET
    @Path("/eligible-team-leaders")
    @Operation(
            summary = "Get eligible team leaders for a fiscal year",
            description = """
                Returns all team leaders who held LEADER roles during the specified fiscal year.
                The fiscal year runs July 1 to June 30. For leaders with multiple role periods
                in the same team, the periods are merged to show the overall effective dates.
                """
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EligibleLeadersResponse.class),
                            examples = @ExampleObject(
                                    name = "Example",
                                    value = """
                                            {
                                              "financialYear": 2025,
                                              "periodStart": "2025-07-01",
                                              "periodEnd": "2026-06-30",
                                              "eligibleLeaders": [
                                                {
                                                  "userUuid": "11111111-1111-1111-1111-111111111111",
                                                  "userName": "Jane Doe",
                                                  "teamUuid": "22222222-2222-2222-2222-222222222222",
                                                  "teamName": "Development Team",
                                                  "startDate": "2025-07-01",
                                                  "endDate": "2026-06-30"
                                                }
                                              ],
                                              "totalLeaders": 1
                                            }
                                            """
                            )
                    )
            ),
            @APIResponse(responseCode = "400", description = "Bad request – invalid financial year")
    })
    public EligibleLeadersResponse getEligibleTeamLeadersByFinancialYear(
            @Parameter(
                    name = "financialYear",
                    description = "FY starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)",
                    required = true
            )
            @QueryParam("financialYear") Integer financialYear,
            @Parameter(
                    name = "teamuuid",
                    description = "Optional team UUID to filter for a specific team",
                    required = false
            )
            @QueryParam("teamuuid") String teamUuid) {

        if (financialYear == null) {
            throw new BadRequestException("financialYear parameter is required");
        }
        if (financialYear < 2000 || financialYear > 2999) {
            throw new BadRequestException("financialYear must be between 2000 and 2999");
        }

        LocalDate fyStart = LocalDate.of(financialYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(financialYear + 1, 6, 30);

        Map<String, EligibleLeader> leaderMap = new HashMap<>();

        // Get teams to process
        List<Team> teams = teamUuid != null ?
            List.of(Team.findById(teamUuid)) :
            teamService.listAll().stream().filter(Team::isTeamleadbonus).toList();


        for (Team team : teams) {
            if (team == null) continue;

            List<TeamRole> roles = TeamRole.find("teamuuid = ?1", team.getUuid()).list();

            for (TeamRole role : roles) {
                if (role.getTeammembertype() != TeamMemberType.LEADER) continue;

                // Check if role overlaps with fiscal year
                LocalDate roleStart = role.getStartdate() != null ? role.getStartdate() : LocalDate.MIN;
                LocalDate roleEnd = role.getEnddate() != null ? role.getEnddate() : LocalDate.MAX;

                if (roleEnd.isBefore(fyStart) || roleStart.isAfter(fyEnd)) {
                    continue; // No overlap with FY
                }

                // Calculate effective period within FY
                LocalDate effectiveStart = roleStart.isBefore(fyStart) ? fyStart : roleStart;
                LocalDate effectiveEnd = roleEnd.isAfter(fyEnd) ? fyEnd : roleEnd;

                String key = role.getUseruuid() + "-" + team.getUuid();
                System.out.println("key = " + key);
                EligibleLeader leader = leaderMap.get(key);

                if (leader == null) {
                    User user = userService.findById(role.getUseruuid(), false);
                    leader = new EligibleLeader(
                            role.getUseruuid(),
                            user != null ? user.getFullname() : role.getUseruuid(),
                            team.getUuid(),
                            team.getName(),
                            effectiveStart,
                            effectiveEnd
                    );
                    leaderMap.put(key, leader);
                } else {
                    // Update dates if we have multiple role periods
                    LocalDate newStart = effectiveStart.isBefore(leader.startDate()) ? effectiveStart : leader.startDate();
                    LocalDate newEnd = effectiveEnd.isAfter(leader.endDate()) ? effectiveEnd : leader.endDate();
                    if (!newStart.equals(leader.startDate()) || !newEnd.equals(leader.endDate())) {
                        // Create updated leader with new dates
                        leader = new EligibleLeader(
                                leader.userUuid(),
                                leader.userName(),
                                leader.teamUuid(),
                                leader.teamName(),
                                newStart,
                                newEnd
                        );
                        leaderMap.put(key, leader);
                    }
                }
            }
        }

        List<EligibleLeader> eligibleLeaders = new ArrayList<>(leaderMap.values());
        System.out.println("eligibleLeaders.size() = " + eligibleLeaders.size());
        eligibleLeaders.sort(Comparator.comparing(EligibleLeader::teamName).thenComparing(EligibleLeader::userName));

        // Data boundary: mask user names and team names when caller lacks required scopes
        boolean maskUserNames = !scopeContext.hasScope("users:read");
        boolean maskTeamNames = !scopeContext.hasScope("teams:read");
        if (maskUserNames || maskTeamNames) {
            eligibleLeaders = eligibleLeaders.stream()
                    .map(l -> new EligibleLeader(
                            l.userUuid(),
                            maskUserNames ? null : l.userName(),
                            l.teamUuid(),
                            maskTeamNames ? null : l.teamName(),
                            l.startDate(),
                            l.endDate()
                    ))
                    .toList();
        }

        return new EligibleLeadersResponse(
                financialYear,
                fyStart,
                fyEnd,
                eligibleLeaders,
                eligibleLeaders.size()
        );
    }

    // ---- Team Dashboard Bonus Endpoints ----

    @GET
    @Path("/team/{teamId}/bonus-projection")
    @RolesAllowed({"dashboard:read"})
    @Operation(
            summary = "Get bonus projection for a team leader",
            description = """
                Returns pool bonus + production bonus projection for the specified team's leader
                in the given fiscal year. Only accessible by the team's current leader.
                Pool bonus: MAX(team_avg_util - 0.65, 0) * 5 * team_factor * price_per_point * 100.
                Production bonus: MAX((own_revenue - prorated_threshold) * 0.20, 0).
                """
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Bonus projection calculated successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = TeamBonusProjectionDTO.class))
            ),
            @APIResponse(responseCode = "400", description = "Bad request – invalid fiscal year"),
            @APIResponse(responseCode = "403", description = "Forbidden – requester is not a leader of this team"),
            @APIResponse(responseCode = "404", description = "Team not found")
    })
    public Response getTeamBonusProjection(
            @Parameter(description = "Team UUID", required = true)
            @PathParam("teamId") String teamId,
            @Parameter(
                    name = "fiscalYear",
                    description = "FY starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)",
                    required = true
            )
            @QueryParam("fiscalYear") Integer fiscalYear) {

        if (fiscalYear == null) {
            throw new BadRequestException("fiscalYear parameter is required");
        }
        if (fiscalYear < 2000 || fiscalYear > 2999) {
            throw new BadRequestException("fiscalYear must be between 2000 and 2999");
        }

        teamBonusProjectionService.validateTeamAccess(teamId);
        TeamBonusProjectionDTO projection = teamBonusProjectionService.getBonusProjection(teamId, fiscalYear);
        return Response.ok(projection).build();
    }

    @GET
    @Path("/team/{teamId}/bonus-all-teams")
    @RolesAllowed({"dashboard:read"})
    @Operation(
            summary = "Get bonus ranking for all bonus-eligible teams",
            description = """
                Returns bonus points and ranking data for all teams where team.teamleadbonus = true.
                The requesting team is marked with isCurrentTeam = true. Used for the ranking comparison chart.
                Only accessible by the team's current leader.
                """
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Ranking data returned successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AllTeamsBonusRankingDTO.class))
            ),
            @APIResponse(responseCode = "400", description = "Bad request – invalid fiscal year"),
            @APIResponse(responseCode = "403", description = "Forbidden – requester is not a leader of this team"),
            @APIResponse(responseCode = "404", description = "Team not found")
    })
    public Response getAllTeamsBonusRanking(
            @Parameter(description = "Team UUID (used to mark isCurrentTeam)", required = true)
            @PathParam("teamId") String teamId,
            @Parameter(
                    name = "fiscalYear",
                    description = "FY starting year (FY YYYY runs from YYYY-07-01 to (YYYY+1)-06-30)",
                    required = true
            )
            @QueryParam("fiscalYear") Integer fiscalYear) {

        if (fiscalYear == null) {
            throw new BadRequestException("fiscalYear parameter is required");
        }
        if (fiscalYear < 2000 || fiscalYear > 2999) {
            throw new BadRequestException("fiscalYear must be between 2000 and 2999");
        }

        teamBonusProjectionService.validateTeamAccess(teamId);
        List<AllTeamsBonusRankingDTO> rankings = teamBonusProjectionService.getAllTeamsBonusRanking(teamId, fiscalYear);
        return Response.ok(rankings).build();
    }
}
