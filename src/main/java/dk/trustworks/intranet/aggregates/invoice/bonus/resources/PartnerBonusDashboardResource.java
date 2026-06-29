package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PartnerBonusBackfillReport;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PartnerDashboardDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PayoutRequestDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PayoutResultDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.PartnerBonusDashboardService;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.PartnerBonusPayoutService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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
@RolesAllowed({"partnerbonus:read"})
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
    @RolesAllowed({"partnerbonus:write"})
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
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "Create partner bonus payouts (sales + production). " +
            "Amounts are recomputed server-side from the un-consumed APPROVED invoices; any " +
            "salesAmount/productionAmount in the request body is ignored. An invoice can only ever " +
            "fund one payout (enforced via the frozen payout event + per-invoice consumed marker).")
    public Response createPayouts(@Valid PayoutRequestDTO request,
                                   @HeaderParam("X-Requested-By") String requestedBy) {
        if (request == null) throw new BadRequestException("Request body required");
        if (request.userUuid() == null || request.userUuid().isBlank()) {
            throw new BadRequestException("userUuid is required");
        }
        if (request.fiscalYear() < 2000 || request.fiscalYear() > 2999) {
            throw new BadRequestException("Invalid fiscal year");
        }
        if (request.payoutMonth() == null || request.payoutMonth().isBlank()) {
            throw new BadRequestException("payoutMonth is required");
        }

        // Friendly pre-check (the real guarantee is the per-track source_reference unique index).
        if (payoutService.hasExistingPayout(request.userUuid(), request.fiscalYear())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Payout already exists for this user and fiscal year"))
                    .build();
        }

        LocalDate month;
        try {
            month = LocalDate.parse(request.payoutMonth()).withDayOfMonth(1);
        } catch (Exception e) {
            throw new BadRequestException("payoutMonth must be an ISO date (yyyy-MM-dd)");
        }

        PayoutResultDTO result = payoutService.payPartner(
                request.userUuid(), month, request.fiscalYear(), requestedBy);

        // Invalidate cache after payout
        dashboardService.invalidateCache(request.fiscalYear());

        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    @POST
    @Path("/payouts/backfill")
    @RolesAllowed({"partnerbonus:write"})
    @Operation(summary = "One-time backfill: stamp the APPROVED invoices that already funded paid " +
            "fiscal years so the per-invoice guard does not re-fund them. dryRun=true (default) " +
            "changes nothing and only returns the reconciliation report.")
    public PartnerBonusBackfillReport backfillPayouts(
            @QueryParam("dryRun") @DefaultValue("true") boolean dryRun,
            @QueryParam("fiscalYearFrom") Integer fiscalYearFrom,
            @QueryParam("fiscalYearTo") Integer fiscalYearTo,
            @HeaderParam("X-Requested-By") String requestedBy) {
        return payoutService.backfillPaidFiscalYears(fiscalYearFrom, fiscalYearTo, dryRun, requestedBy);
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
