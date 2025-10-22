package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.ContractRuleSetDTO;
import dk.trustworks.intranet.contracts.dto.PricingOverrideDTO;
import dk.trustworks.intranet.contracts.dto.RateOverrideDTO;
import dk.trustworks.intranet.contracts.dto.ValidationOverrideDTO;
import dk.trustworks.intranet.contracts.model.ContractRuleAudit;
import dk.trustworks.intranet.contracts.services.ContractOverrideFeatureService;
import dk.trustworks.intranet.contracts.services.ContractRuleAuditService;
import dk.trustworks.intranet.contracts.services.ContractRuleOverrideService;
import dk.trustworks.intranet.contracts.services.RuleResolutionService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

/**
 * REST API for managing contract rule overrides.
 *
 * <p>This resource provides endpoints for:
 * <ul>
 *   <li>Retrieving effective rules (base + overrides merged)</li>
 *   <li>CRUD operations on validation rule overrides</li>
 *   <li>CRUD operations on rate adjustment overrides</li>
 *   <li>CRUD operations on pricing rule overrides</li>
 *   <li>Querying audit history</li>
 * </ul>
 *
 * <p><b>Base Path:</b> /api/contracts/{contractUuid}/rules
 *
 * <p><b>Feature Flags:</b>
 * - All endpoints check feature flags before execution
 * - Returns 404 if feature is disabled for the contract
 * - Returns 403 if API is in read-only mode (for mutations)
 *
 * <p><b>Authentication:</b>
 * - TODO: Add authentication/authorization annotations
 * - Required roles: MANAGER or ADMIN for mutations
 * - Required roles: USER for read operations
 *
 * @see ContractRuleOverrideService
 * @see RuleResolutionService
 * @see ContractRuleAuditService
 */
@Path("/api/contracts/{contractUuid}/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@JBossLog
@Tag(name = "Contract Rule Overrides", description = "Manage contract-specific rule overrides")
public class ContractRuleOverrideResource {

    @Inject
    ContractRuleOverrideService overrideService;

    @Inject
    RuleResolutionService resolutionService;

    @Inject
    ContractRuleAuditService auditService;

    @Inject
    ContractOverrideFeatureService featureService;

    // ===== Effective Rules (Merged View) =====

    @GET
    @Path("/effective")
    @Operation(summary = "Get effective rule set", description = "Returns all effective rules for a contract with overrides applied")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Effective rules retrieved successfully",
            content = @Content(schema = @Schema(implementation = ContractRuleSetDTO.class))),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled")
    })
    public Response getEffectiveRules(
        @PathParam("contractUuid")
        @Parameter(description = "Contract UUID", required = true)
        String contractUuid,

        @QueryParam("date")
        @Parameter(description = "Effective date (default: today)", example = "2025-01-20")
        LocalDate date
    ) {
        log.infof("GET /api/contracts/%s/rules/effective?date=%s", contractUuid, date);

        // Feature flag check
        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            log.warnf("Feature disabled for contract %s", contractUuid);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        ContractRuleSetDTO ruleSet = resolutionService.getEffectiveRuleSet(contractUuid, effectiveDate);

        return Response.ok(ruleSet).build();
    }

    // ===== Validation Overrides =====

    @GET
    @Path("/validation")
    @Operation(summary = "Get validation overrides", description = "Returns all validation rule overrides for a contract")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Validation overrides retrieved successfully"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled")
    })
    public Response getValidationOverrides(
        @PathParam("contractUuid") String contractUuid
    ) {
        log.infof("GET /api/contracts/%s/rules/validation", contractUuid);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<ValidationOverrideDTO> overrides = overrideService.getValidationOverrides(contractUuid);
        return Response.ok(overrides).build();
    }

    @POST
    @Path("/validation")
    @Operation(summary = "Create validation override", description = "Creates a new validation rule override for a contract")
    @APIResponses(value = {
        @APIResponse(responseCode = "201", description = "Validation override created successfully"),
        @APIResponse(responseCode = "400", description = "Invalid request data"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled"),
        @APIResponse(responseCode = "409", description = "Override already exists")
    })
    public Response createValidationOverride(
        @PathParam("contractUuid") String contractUuid,
        @Valid ValidationOverrideDTO dto
    ) {
        log.infof("POST /api/contracts/%s/rules/validation", contractUuid);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            ValidationOverrideDTO created = overrideService.createValidationOverride(contractUuid, dto);
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @PUT
    @Path("/validation/{id}")
    @Operation(summary = "Update validation override", description = "Updates an existing validation rule override")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Validation override updated successfully"),
        @APIResponse(responseCode = "400", description = "Invalid request data"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Override not found")
    })
    public Response updateValidationOverride(
        @PathParam("contractUuid") String contractUuid,
        @PathParam("id") Integer id,
        @Valid ValidationOverrideDTO dto
    ) {
        log.infof("PUT /api/contracts/%s/rules/validation/%d", contractUuid, id);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            ValidationOverrideDTO updated = overrideService.updateValidationOverride(contractUuid, id, dto);
            return Response.ok(updated).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/validation/{id}")
    @Operation(summary = "Delete validation override", description = "Deletes (soft delete) a validation rule override")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "Validation override deleted successfully"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Override not found")
    })
    public Response deleteValidationOverride(
        @PathParam("contractUuid") String contractUuid,
        @PathParam("id") Integer id
    ) {
        log.infof("DELETE /api/contracts/%s/rules/validation/%d", contractUuid, id);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            overrideService.deleteValidationOverride(contractUuid, id);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    // ===== Rate Adjustment Overrides =====

    @GET
    @Path("/rate")
    @Operation(summary = "Get rate adjustment overrides", description = "Returns rate adjustment overrides for a contract")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Rate adjustment overrides retrieved successfully"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled")
    })
    public Response getRateOverrides(
        @PathParam("contractUuid") String contractUuid,
        @QueryParam("date") LocalDate date
    ) {
        log.infof("GET /api/contracts/%s/rules/rate?date=%s", contractUuid, date);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<RateOverrideDTO> overrides = overrideService.getRateOverrides(contractUuid, date);
        return Response.ok(overrides).build();
    }

    @POST
    @Path("/rate")
    @Operation(summary = "Create rate adjustment override", description = "Creates a new rate adjustment override")
    @APIResponses(value = {
        @APIResponse(responseCode = "201", description = "Rate adjustment override created successfully"),
        @APIResponse(responseCode = "400", description = "Invalid request data"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled"),
        @APIResponse(responseCode = "409", description = "Override already exists")
    })
    public Response createRateOverride(
        @PathParam("contractUuid") String contractUuid,
        @Valid RateOverrideDTO dto
    ) {
        log.infof("POST /api/contracts/%s/rules/rate", contractUuid);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            RateOverrideDTO created = overrideService.createRateOverride(contractUuid, dto);
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @PUT
    @Path("/rate/{id}")
    @Operation(summary = "Update rate adjustment override", description = "Updates an existing rate adjustment override")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Rate adjustment override updated successfully"),
        @APIResponse(responseCode = "400", description = "Invalid request data"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Override not found")
    })
    public Response updateRateOverride(
        @PathParam("contractUuid") String contractUuid,
        @PathParam("id") Integer id,
        @Valid RateOverrideDTO dto
    ) {
        log.infof("PUT /api/contracts/%s/rules/rate/%d", contractUuid, id);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            RateOverrideDTO updated = overrideService.updateRateOverride(contractUuid, id, dto);
            return Response.ok(updated).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/rate/{id}")
    @Operation(summary = "Delete rate adjustment override", description = "Deletes (soft delete) a rate adjustment override")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "Rate adjustment override deleted successfully"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Override not found")
    })
    public Response deleteRateOverride(
        @PathParam("contractUuid") String contractUuid,
        @PathParam("id") Integer id
    ) {
        log.infof("DELETE /api/contracts/%s/rules/rate/%d", contractUuid, id);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            overrideService.deleteRateOverride(contractUuid, id);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    // ===== Pricing Rule Overrides =====

    @GET
    @Path("/pricing")
    @Operation(summary = "Get pricing rule overrides", description = "Returns pricing rule overrides for a contract")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Pricing rule overrides retrieved successfully"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled")
    })
    public Response getPricingOverrides(
        @PathParam("contractUuid") String contractUuid,
        @QueryParam("date") LocalDate date
    ) {
        log.infof("GET /api/contracts/%s/rules/pricing?date=%s", contractUuid, date);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<PricingOverrideDTO> overrides = overrideService.getPricingOverrides(contractUuid, date);
        return Response.ok(overrides).build();
    }

    @POST
    @Path("/pricing")
    @Operation(summary = "Create pricing rule override", description = "Creates a new pricing rule override")
    @APIResponses(value = {
        @APIResponse(responseCode = "201", description = "Pricing rule override created successfully"),
        @APIResponse(responseCode = "400", description = "Invalid request data"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled"),
        @APIResponse(responseCode = "409", description = "Override already exists")
    })
    public Response createPricingOverride(
        @PathParam("contractUuid") String contractUuid,
        @Valid PricingOverrideDTO dto
    ) {
        log.infof("POST /api/contracts/%s/rules/pricing", contractUuid);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            PricingOverrideDTO created = overrideService.createPricingOverride(contractUuid, dto);
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @PUT
    @Path("/pricing/{id}")
    @Operation(summary = "Update pricing rule override", description = "Updates an existing pricing rule override")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Pricing rule override updated successfully"),
        @APIResponse(responseCode = "400", description = "Invalid request data"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Override not found")
    })
    public Response updatePricingOverride(
        @PathParam("contractUuid") String contractUuid,
        @PathParam("id") Integer id,
        @Valid PricingOverrideDTO dto
    ) {
        log.infof("PUT /api/contracts/%s/rules/pricing/%d", contractUuid, id);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            PricingOverrideDTO updated = overrideService.updatePricingOverride(contractUuid, id, dto);
            return Response.ok(updated).build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/pricing/{id}")
    @Operation(summary = "Delete pricing rule override", description = "Deletes (soft delete) a pricing rule override")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "Pricing rule override deleted successfully"),
        @APIResponse(responseCode = "403", description = "API is in read-only mode"),
        @APIResponse(responseCode = "404", description = "Override not found")
    })
    public Response deletePricingOverride(
        @PathParam("contractUuid") String contractUuid,
        @PathParam("id") Integer id
    ) {
        log.infof("DELETE /api/contracts/%s/rules/pricing/%d", contractUuid, id);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (featureService.isApiReadOnly()) {
            return Response.status(Response.Status.FORBIDDEN)
                .entity("API is in read-only mode")
                .build();
        }

        try {
            overrideService.deletePricingOverride(contractUuid, id);
            return Response.noContent().build();
        } catch (NotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    // ===== Audit Log =====

    @GET
    @Path("/audit")
    @Operation(summary = "Get audit history", description = "Returns audit trail for contract rule overrides")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "Audit history retrieved successfully"),
        @APIResponse(responseCode = "404", description = "Contract not found or feature disabled")
    })
    public Response getAuditHistory(
        @PathParam("contractUuid") String contractUuid,
        @QueryParam("ruleId") String ruleId,
        @QueryParam("limit") @DefaultValue("100") int limit
    ) {
        log.infof("GET /api/contracts/%s/rules/audit?ruleId=%s&limit=%d", contractUuid, ruleId, limit);

        if (!featureService.isApiEnabled() || !featureService.isEnabledForContract(contractUuid)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<ContractRuleAudit> audits;
        if (ruleId != null) {
            audits = auditService.getAuditForRule(contractUuid, ruleId);
        } else {
            audits = auditService.getRecentAudit(contractUuid, limit);
        }

        return Response.ok(audits).build();
    }
}
