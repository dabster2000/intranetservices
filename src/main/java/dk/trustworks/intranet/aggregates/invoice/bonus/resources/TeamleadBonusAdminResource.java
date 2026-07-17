package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.*;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamleadBonusAdminService;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamleadBonusConfigService;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamleadBonusDashboardService;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamleadBonusPayoutService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Teamlead-bonus admin dashboard API (spec §3). Read endpoints require {@code partnerbonus:read};
 * mutations additionally require {@code partnerbonus:write}. Identity for audit fields is taken from
 * the {@code X-Requested-By} header via {@link RequestHeaderHolder}.
 */
@Tag(name = "teamlead-bonus-dashboard", description = "Teamlead bonus admin dashboard")
@Path("/invoices/bonuses/teamlead-dashboard")
@RequestScoped
@RolesAllowed({"partnerbonus:read"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TeamleadBonusAdminResource {

    @Inject TeamleadBonusDashboardService dashboardService;
    @Inject TeamleadBonusConfigService configService;
    @Inject TeamleadBonusAdminService adminService;
    @Inject TeamleadBonusPayoutService payoutService;
    @Inject RequestHeaderHolder requestHeaderHolder;

    // =====================================================================
    // Dashboard
    // =====================================================================

    @GET
    @Operation(summary = "Full teamlead bonus dashboard for a fiscal year")
    public TeamleadDashboardDTO getDashboard(@QueryParam("fiscalYear") Integer fiscalYear) {
        return dashboardService.getDashboard(requireFiscalYear(fiscalYear));
    }

    @GET
    @Path("/{teamId}/monthly-detail")
    @Operation(summary = "Per-month utilization drill-down for a team (validate bonus inputs)")
    public TeamleadMonthlyDetailDTO getMonthlyDetail(@PathParam("teamId") String teamId,
                                                     @QueryParam("fiscalYear") Integer fiscalYear) {
        return dashboardService.getMonthlyDetail(teamId, requireFiscalYear(fiscalYear));
    }

    // =====================================================================
    // Config
    // =====================================================================

    @GET
    @Path("/config")
    @Operation(summary = "Effective teamlead bonus configuration for a fiscal year")
    public TeamleadBonusConfigDTO getConfig(@QueryParam("fiscalYear") Integer fiscalYear) {
        return configService.getEffectiveConfig(requireFiscalYear(fiscalYear));
    }

    @PUT
    @Path("/config")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Upsert the teamlead bonus configuration for a fiscal year")
    public TeamleadBonusConfigDTO upsertConfig(@Valid @NotNull TeamleadBonusConfigDTO request) {
        return configService.upsertConfig(request, requestedBy());
    }

    // =====================================================================
    // Adjustments
    // =====================================================================

    @GET
    @Path("/adjustments")
    @Operation(summary = "List admin adjustments for a fiscal year")
    public List<TeamleadBonusAdjustmentDTO> listAdjustments(@QueryParam("fiscalYear") Integer fiscalYear) {
        return adminService.listAdjustments(requireFiscalYear(fiscalYear));
    }

    @POST
    @Path("/adjustments")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Create an admin adjustment")
    public Response createAdjustment(@Valid @NotNull TeamleadAdjustmentRequest request) {
        requireFiscalYear(request.fiscalYear());
        TeamleadBonusAdjustmentDTO created = adminService.createAdjustment(request, requestedBy());
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/adjustments/{uuid}")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Update an admin adjustment")
    public TeamleadBonusAdjustmentDTO updateAdjustment(@PathParam("uuid") String uuid,
                                                       @Valid @NotNull TeamleadAdjustmentRequest request) {
        requireFiscalYear(request.fiscalYear());
        return adminService.updateAdjustment(uuid, request, requestedBy());
    }

    @DELETE
    @Path("/adjustments/{uuid}")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Delete an admin adjustment")
    public Response deleteAdjustment(@PathParam("uuid") String uuid) {
        adminService.deleteAdjustment(uuid);
        return Response.noContent().build();
    }

    // =====================================================================
    // Member overrides (editable calculation sources)
    // =====================================================================

    @PUT
    @Path("/{teamId}/member-overrides")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Upsert a member-month inclusion override for a team")
    public TeamleadMemberOverrideDTO upsertMemberOverride(@PathParam("teamId") String teamId,
                                                          @Valid @NotNull TeamleadMemberOverrideRequest request) {
        return adminService.upsertMemberOverride(teamId, request, requestedBy());
    }

    @DELETE
    @Path("/{teamId}/member-overrides/{useruuid}/{month}")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Delete a member-month inclusion override (idempotent)")
    public Response deleteMemberOverride(@PathParam("teamId") String teamId,
                                         @PathParam("useruuid") String useruuid,
                                         @PathParam("month") String month) {
        adminService.deleteMemberOverride(teamId, useruuid, month);
        return Response.noContent().build();
    }

    // =====================================================================
    // Leader exclusions (editable calculation sources)
    // =====================================================================

    @GET
    @Path("/leader-exclusions")
    @Operation(summary = "List leader exclusions for a fiscal year")
    public List<TeamleadLeaderExclusionDTO> listLeaderExclusions(@QueryParam("fiscalYear") Integer fiscalYear) {
        return adminService.listLeaderExclusions(requireFiscalYear(fiscalYear));
    }

    @POST
    @Path("/leader-exclusions")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Create a leader exclusion (idempotent)")
    public Response createLeaderExclusion(@Valid @NotNull TeamleadLeaderExclusionRequest request) {
        requireFiscalYear(request.fiscalYear());
        TeamleadBonusAdminService.LeaderExclusionResult result =
                adminService.createLeaderExclusion(request, requestedBy());
        Response.Status status = result.created() ? Response.Status.CREATED : Response.Status.OK;
        return Response.status(status).entity(result.dto()).build();
    }

    @DELETE
    @Path("/leader-exclusions")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Delete a leader exclusion (idempotent)")
    public Response deleteLeaderExclusion(@QueryParam("fiscalYear") Integer fiscalYear,
                                          @QueryParam("teamId") String teamId,
                                          @QueryParam("useruuid") String useruuid) {
        int fy = requireFiscalYear(fiscalYear);
        if (teamId == null || teamId.isBlank()) throw new BadRequestException("teamId is required");
        if (useruuid == null || useruuid.isBlank()) throw new BadRequestException("useruuid is required");
        adminService.deleteLeaderExclusion(fy, teamId, useruuid);
        return Response.noContent().build();
    }

    // =====================================================================
    // Salary exclusions
    // =====================================================================

    @GET
    @Path("/salary-exclusions")
    @Operation(summary = "List salary-exclusion overrides for a fiscal year")
    public List<TeamleadBonusSalaryExclusionDTO> listExclusions(@QueryParam("fiscalYear") Integer fiscalYear) {
        return adminService.listExclusions(requireFiscalYear(fiscalYear));
    }

    @POST
    @Path("/salary-exclusions")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Create a salary-exclusion override")
    public Response createExclusion(@Valid @NotNull TeamleadSalaryExclusionRequest request) {
        requireFiscalYear(request.fiscalYear());
        TeamleadBonusSalaryExclusionDTO created = adminService.createExclusion(request, requestedBy());
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @DELETE
    @Path("/salary-exclusions/{uuid}")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Delete a salary-exclusion override")
    public Response deleteExclusion(@PathParam("uuid") String uuid) {
        adminService.deleteExclusion(uuid);
        return Response.noContent().build();
    }

    // =====================================================================
    // Payouts
    // =====================================================================

    @POST
    @Path("/payouts")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Create a teamlead bonus payout (recomputed server-side)")
    public Response createPayout(@Valid @NotNull TeamleadPayoutRequest request) {
        int fiscalYear = requireFiscalYear(request.fiscalYear());
        if (request.userUuid() == null || request.userUuid().isBlank()) {
            throw new BadRequestException("userUuid is required");
        }

        // Friendly pre-check; the unique (fiscal_year, useruuid) constraint is the hard backstop.
        if (payoutService.hasExistingPayout(fiscalYear, request.userUuid())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Payout already exists for this leader and fiscal year"))
                    .build();
        }

        LocalDate month;
        try {
            month = LocalDate.parse(request.payoutMonth()).withDayOfMonth(1);
        } catch (Exception e) {
            throw new BadRequestException("payoutMonth must be an ISO date (yyyy-MM-dd)");
        }

        TeamleadPayoutResultDTO result = payoutService.payLeader(
                request.userUuid(), fiscalYear, month, requestedBy());
        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private int requireFiscalYear(Integer fiscalYear) {
        if (fiscalYear == null) throw new BadRequestException("fiscalYear is required");
        if (fiscalYear < 2000 || fiscalYear > 2999) throw new BadRequestException("fiscalYear must be 2000-2999");
        return fiscalYear;
    }

    private String requestedBy() {
        // Audit columns are VARCHAR(100); the header should be a 36-char UUID, but guard against
        // oversized values failing at the DB layer as a 500.
        String userUuid = requestHeaderHolder.getUserUuid();
        return userUuid != null && userUuid.length() > 100 ? userUuid.substring(0, 100) : userUuid;
    }
}
