package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PartnerDashboardDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PayoutRequestDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.PartnerBonusDashboardService;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.PartnerBonusPayoutService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "partner-bonus-dashboard", description = "Partner bonus admin dashboard")
@Path("/invoices/bonuses/partner-dashboard")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PartnerBonusDashboardResource {

    @Inject
    PartnerBonusDashboardService dashboardService;

    @Inject
    PartnerBonusPayoutService payoutService;

    @GET
    @Operation(summary = "Full partner dashboard data for a fiscal year")
    public PartnerDashboardDTO getDashboard(
            @QueryParam("fiscalYear") Integer fiscalYear,
            @QueryParam("groups") String groups) {
        if (fiscalYear == null) throw new BadRequestException("fiscalYear is required");
        if (fiscalYear < 2000 || fiscalYear > 2999) throw new BadRequestException("fiscalYear must be 2000-2999");

        Set<String> groupUuids = parseGroupUuids(groups);
        return dashboardService.loadDashboard(fiscalYear, groupUuids);
    }

    @POST
    @Path("/invalidate-cache")
    @Operation(summary = "Clear dashboard cache for a fiscal year")
    public Response invalidateCache(@QueryParam("fiscalYear") Integer fiscalYear) {
        if (fiscalYear == null) throw new BadRequestException("fiscalYear is required");
        dashboardService.invalidateCache(fiscalYear);
        return Response.ok(Map.of("message", "Cache invalidated for FY " + fiscalYear)).build();
    }

    @GET
    @Path("/prepaid/{userUuid}")
    @Operation(summary = "Get prepaid bonus amount for a user in a fiscal year")
    public Map<String, Object> getPrepaidBonus(
            @PathParam("userUuid") String userUuid,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        if (fiscalYear == null) throw new BadRequestException("fiscalYear is required");
        double amount = payoutService.calculatePrepaidBonuses(userUuid, fiscalYear);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userUuid", userUuid);
        result.put("fiscalYear", fiscalYear);
        result.put("prepaidAmount", amount);
        return result;
    }

    @GET
    @Path("/payout-status/{userUuid}")
    @Operation(summary = "Check if payout exists for a user in a fiscal year")
    public Map<String, Object> getPayoutStatus(
            @PathParam("userUuid") String userUuid,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        if (fiscalYear == null) throw new BadRequestException("fiscalYear is required");
        boolean exists = payoutService.hasExistingPayout(userUuid, fiscalYear);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userUuid", userUuid);
        result.put("fiscalYear", fiscalYear);
        result.put("payoutExists", exists);
        return result;
    }

    @POST
    @Path("/payouts")
    @Operation(summary = "Create partner bonus payouts (sales + production)")
    public Response createPayouts(PayoutRequestDTO request,
                                   @HeaderParam("X-Requested-By") String requestedBy) {
        if (request == null) throw new BadRequestException("Request body required");
        if (request.userUuid() == null || request.userUuid().isBlank()) {
            throw new BadRequestException("userUuid is required");
        }
        if (request.fiscalYear() < 2000 || request.fiscalYear() > 2999) {
            throw new BadRequestException("Invalid fiscal year");
        }

        // Check for existing payout (idempotency)
        if (payoutService.hasExistingPayout(request.userUuid(), request.fiscalYear())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Payout already exists for this user and fiscal year"))
                    .build();
        }

        LocalDate month = LocalDate.parse(request.payoutMonth()).withDayOfMonth(1);
        payoutService.createPartnerPayouts(
                request.userUuid(),
                request.salesAmount(),
                request.productionAmount(),
                month,
                request.fiscalYear()
        );

        // Invalidate cache after payout
        dashboardService.invalidateCache(request.fiscalYear());

        return Response.status(Response.Status.CREATED)
                .entity(Map.of("message", "Payouts created successfully"))
                .build();
    }

    // --- helpers ---

    private static Set<String> parseGroupUuids(String groups) {
        if (groups == null || groups.isBlank()) return Set.of();
        return Arrays.stream(groups.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
