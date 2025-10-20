package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.CreateValidationRuleRequest;
import dk.trustworks.intranet.contracts.dto.UpdateValidationRuleRequest;
import dk.trustworks.intranet.contracts.dto.ValidationRuleDTO;
import dk.trustworks.intranet.contracts.services.ContractValidationRuleService;
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
 * REST API for managing contract validation rules.
 * Validation rules enforce business constraints like required notes, minimum hours, etc.
 *
 * All endpoints require SYSTEM role (admin access).
 */
@Tag(name = "Contract Validation Rules", description = "Manage validation rules for contract types")
@Path("/api/contract-types/{contractTypeCode}/validation-rules")
@JBossLog
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class ContractValidationRuleResource {

    @Inject
    ContractValidationRuleService validationRuleService;

    /**
     * List all validation rules for a contract type.
     *
     * @param contractTypeCode The contract type code
     * @param includeInactive Whether to include inactive rules (default: false)
     * @return List of validation rule DTOs
     */
    @GET
    @Operation(summary = "List validation rules", description = "Returns all validation rules for a contract type")
    @APIResponse(
        responseCode = "200",
        description = "List of validation rules",
        content = @Content(schema = @Schema(implementation = ValidationRuleDTO.class))
    )
    public Response listAll(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Include inactive rules") @QueryParam("includeInactive") @DefaultValue("false") boolean includeInactive) {
        log.info("ContractValidationRuleResource.listAll");
        log.info("contractTypeCode = " + contractTypeCode + ", includeInactive = " + includeInactive);

        List<ValidationRuleDTO> rules = validationRuleService.listAll(contractTypeCode, includeInactive);
        return Response.ok(rules).build();
    }

    /**
     * Get a specific validation rule by rule ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return Validation rule DTO
     */
    @GET
    @Path("/{ruleId}")
    @Operation(summary = "Get validation rule by ID", description = "Returns a specific validation rule")
    @APIResponse(responseCode = "200", description = "Validation rule found")
    @APIResponse(responseCode = "404", description = "Validation rule not found")
    public Response getByRuleId(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Rule ID") @PathParam("ruleId") String ruleId) {
        log.info("ContractValidationRuleResource.getByRuleId");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        ValidationRuleDTO dto = validationRuleService.findByRuleId(contractTypeCode, ruleId);
        return Response.ok(dto).build();
    }

    /**
     * Create a new validation rule.
     *
     * @param contractTypeCode The contract type code
     * @param request The validation rule data
     * @return Created validation rule DTO
     */
    @POST
    @Operation(summary = "Create validation rule", description = "Creates a new validation rule for a contract type")
    @APIResponse(responseCode = "201", description = "Validation rule created")
    @APIResponse(responseCode = "400", description = "Invalid request or duplicate rule ID")
    public Response create(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Valid CreateValidationRuleRequest request) {
        log.info("ContractValidationRuleResource.create");
        log.info("contractTypeCode = " + contractTypeCode + ", request = " + request);

        ValidationRuleDTO created = validationRuleService.create(contractTypeCode, request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    /**
     * Update an existing validation rule.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @param request The updated data
     * @return Updated validation rule DTO
     */
    @PUT
    @Path("/{ruleId}")
    @Operation(summary = "Update validation rule", description = "Updates an existing validation rule")
    @APIResponse(responseCode = "200", description = "Validation rule updated")
    @APIResponse(responseCode = "404", description = "Validation rule not found")
    public Response update(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Rule ID") @PathParam("ruleId") String ruleId,
            @Valid UpdateValidationRuleRequest request) {
        log.info("ContractValidationRuleResource.update");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId + ", request = " + request);

        ValidationRuleDTO updated = validationRuleService.update(contractTypeCode, ruleId, request);
        return Response.ok(updated).build();
    }

    /**
     * Soft delete a validation rule.
     * Sets active=false but preserves the record.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return 204 No Content
     */
    @DELETE
    @Path("/{ruleId}")
    @Operation(summary = "Delete validation rule", description = "Soft deletes a validation rule (sets active=false)")
    @APIResponse(responseCode = "204", description = "Validation rule deleted")
    @APIResponse(responseCode = "404", description = "Validation rule not found")
    public Response delete(
            @Parameter(description = "Contract type code") @PathParam("contractTypeCode") String contractTypeCode,
            @Parameter(description = "Rule ID") @PathParam("ruleId") String ruleId) {
        log.info("ContractValidationRuleResource.delete");
        log.info("contractTypeCode = " + contractTypeCode + ", ruleId = " + ruleId);

        validationRuleService.softDelete(contractTypeCode, ruleId);
        return Response.noContent().build();
    }
}
