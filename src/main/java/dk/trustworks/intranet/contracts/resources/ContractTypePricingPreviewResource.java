package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.PricingPreviewRequest;
import dk.trustworks.intranet.contracts.dto.PricingPreviewResponse;
import dk.trustworks.intranet.contracts.services.PricingPreviewService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Pricing simulation endpoint for the framework-agreement simulator (spec §9.1).
 *
 * <p>Runs the production pricing-engine math in explain mode against a synthetic
 * invoice — every rule is reported in execution order, including skipped ones with
 * their skip reason, so the UI shows exactly what a real invoice would experience.
 */
@Tag(name = "Contract Types", description = "Manage dynamic contract type definitions")
@Path("/api/contract-types")
@JBossLog
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"contracts:read"})
public class ContractTypePricingPreviewResource {

    @Inject
    PricingPreviewService pricingPreviewService;

    /**
     * Simulate the pricing engine for a contract type at a given amount and date.
     *
     * @param code    The contract type code (e.g., "SKI0215_2025")
     * @param request Amount, invoice date, optional contract UUID (param-key resolution) and discount percent
     * @return Full explain-mode breakdown: every step (executed and skipped) plus totals
     */
    @POST
    @Path("/{code}/pricing-preview")
    @Operation(summary = "Simulate the pricing engine for a contract type",
            description = "Runs the production pricing engine in explain mode against a synthetic invoice. "
                    + "Lists every rule in execution order — including disabled, not-yet-valid, expired and "
                    + "zero-effect rules with a skip reason — plus the auto-injected invoice-discount fallback, "
                    + "the zero clamp and VAT.")
    @APIResponse(
            responseCode = "200",
            description = "Explain-mode pricing breakdown",
            content = @Content(schema = @Schema(implementation = PricingPreviewResponse.class))
    )
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response preview(
            @Parameter(description = "Contract type code", required = true) @PathParam("code") String code,
            @Valid PricingPreviewRequest request) {
        log.debugf("Pricing preview for contract type %s: amount=%s, invoiceDate=%s, contractUuid=%s, discountPct=%s",
                code, request.getAmount(), request.getInvoiceDate(), request.getContractUuid(), request.getDiscountPct());

        PricingPreviewResponse response = pricingPreviewService.preview(code, request);
        return Response.ok(response).build();
    }
}
