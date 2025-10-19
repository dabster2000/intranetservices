package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.*;
import dk.trustworks.intranet.contracts.services.PricingRuleStepService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
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
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST API for managing pricing rule steps.
 * Allows creating, reading, updating, and deleting pricing rules for contract types.
 *
 * All endpoints require SYSTEM role (admin access).
 */
@Tag(name = "Pricing Rules", description = "Manage pricing rules for contract types")
@Path("/api/contract-types/{contractTypeCode}/rules")
@JBossLog
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class PricingRuleResource {

    @Inject
    PricingRuleStepService pricingRuleService;

    /**
     * List all rules for a contract type.
     *
     * @param contractTypeCode The contract type code
     * @param includeInactive Whether to include inactive rules (default: false)
     * @return List of rule DTOs
     */
    @GET
    @Operation(summary = "List all rules", description = "Returns all pricing rules for a contract type")
    @APIResponse(
        responseCode = "200",
        description = "List of pricing rules",
        content = @Content(schema = @Schema(implementation = PricingRuleStepDTO.class))
    )
    public Response listAll(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Include inactive rules") @QueryParam("includeInactive") @DefaultValue("false") boolean includeInactive) {
        log.info("PricingRuleResource.listAll");
        log.info("contractTypeCode = " + contractTypeCode + ", includeInactive = " + includeInactive);

        List<PricingRuleStepDTO> rules = pricingRuleService.getRulesForContractType(contractTypeCode, includeInactive);
        return Response.ok(rules).build();
    }

    /**
     * Get a specific rule by ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return Rule DTO
     */
    @GET
    @Path("/{ruleId}")
    @Operation(summary = "Get rule by ID", description = "Returns a specific pricing rule")
    @APIResponse(responseCode = "200", description = "Rule found")
    @APIResponse(responseCode = "404", description = "Rule not found")
    public Response getById(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Rule ID") @PathParam("ruleId") String ruleId) {
        log.info("PricingRuleResource.getById");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        PricingRuleStepDTO rule = pricingRuleService.getRule(contractTypeCode, ruleId);
        return Response.ok(rule).build();
    }

    /**
     * Create a new pricing rule.
     *
     * @param contractTypeCode The contract type code
     * @param request The rule data
     * @return Created rule DTO
     */
    @POST
    @Operation(summary = "Create rule", description = "Creates a new pricing rule for a contract type")
    @APIResponse(responseCode = "201", description = "Rule created")
    @APIResponse(responseCode = "400", description = "Invalid request or duplicate rule ID")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response create(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Valid CreateRuleStepRequest request) {
        log.info("PricingRuleResource.create");
        log.info("contractTypeCode = " + contractTypeCode + ", request = " + request);

        PricingRuleStepDTO created = pricingRuleService.createRule(contractTypeCode, request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    /**
     * Create multiple rules at once (bulk operation).
     *
     * @param contractTypeCode The contract type code
     * @param request The bulk request with multiple rules
     * @return List of created rule DTOs
     */
    @POST
    @Path("/bulk")
    @Operation(summary = "Create rules in bulk", description = "Creates multiple pricing rules at once")
    @APIResponse(responseCode = "201", description = "Rules created")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response createBulk(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Valid BulkCreateRulesRequest request) {
        log.info("PricingRuleResource.createBulk");
        log.info("contractTypeCode = " + contractTypeCode + ", rulesCount = " + request.getRules().size());

        List<PricingRuleStepDTO> created = pricingRuleService.createRulesBulk(contractTypeCode, request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    /**
     * Update an existing pricing rule.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @param request The updated data
     * @return Updated rule DTO
     */
    @PUT
    @Path("/{ruleId}")
    @Operation(summary = "Update rule", description = "Updates an existing pricing rule")
    @APIResponse(responseCode = "200", description = "Rule updated")
    @APIResponse(responseCode = "404", description = "Rule not found")
    @APIResponse(responseCode = "400", description = "Invalid request")
    public Response update(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Rule ID") @PathParam("ruleId") String ruleId,
            @Valid UpdateRuleStepRequest request) {
        log.info("PricingRuleResource.update");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        PricingRuleStepDTO updated = pricingRuleService.updateRule(contractTypeCode, ruleId, request);
        return Response.ok(updated).build();
    }

    /**
     * Delete a pricing rule.
     * Performs a soft delete (sets active=false).
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return 204 No Content
     */
    @DELETE
    @Path("/{ruleId}")
    @Operation(summary = "Delete rule", description = "Soft deletes a pricing rule (sets active=false)")
    @APIResponse(responseCode = "204", description = "Rule deleted")
    @APIResponse(responseCode = "404", description = "Rule not found")
    public Response delete(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Rule ID") @PathParam("ruleId") String ruleId) {
        log.info("PricingRuleResource.delete");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        pricingRuleService.deleteRule(contractTypeCode, ruleId);
        return Response.noContent().build();
    }
}
