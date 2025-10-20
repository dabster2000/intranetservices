package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.dto.*;
import dk.trustworks.intranet.contracts.services.ContractTypeDefinitionService;
import dk.trustworks.intranet.contracts.services.ContractValidationRuleService;
import dk.trustworks.intranet.contracts.services.ContractRateAdjustmentService;
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
 * REST API for managing dynamic contract type definitions.
 * Allows creating, reading, updating, and deleting contract types via HTTP.
 *
 * All endpoints require SYSTEM role (admin access).
 */
@Tag(name = "Contract Types", description = "Manage dynamic contract type definitions")
@Path("/api/contract-types")
@JBossLog
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class ContractTypeResource {

    @Inject
    ContractTypeDefinitionService contractTypeService;

    @Inject
    PricingRuleStepService pricingRuleService;

    @Inject
    ContractValidationRuleService validationRuleService;

    @Inject
    ContractRateAdjustmentService rateAdjustmentService;

    /**
     * List all contract types.
     *
     * @param includeInactive Whether to include inactive types (default: false)
     * @return List of contract type DTOs
     */
    @GET
    @Operation(summary = "List all contract types", description = "Returns a list of all contract type definitions")
    @APIResponse(
        responseCode = "200",
        description = "List of contract types",
        content = @Content(schema = @Schema(implementation = ContractTypeDefinitionDTO.class))
    )
    public Response listAll(
            @Parameter(description = "Include inactive types") @QueryParam("includeInactive") @DefaultValue("false") boolean includeInactive) {
        log.info("ContractTypeResource.listAll");
        log.info("includeInactive = " + includeInactive);

        List<ContractTypeDefinitionDTO> types = contractTypeService.listAll(includeInactive);
        return Response.ok(types).build();
    }

    /**
     * Get a specific contract type by code.
     *
     * @param code The contract type code
     * @return Contract type DTO
     */
    @GET
    @Path("/{code}")
    @Operation(summary = "Get contract type by code", description = "Returns a specific contract type definition")
    @APIResponse(responseCode = "200", description = "Contract type found")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response getByCode(@Parameter(description = "Contract type code") @PathParam("code") String code) {
        log.info("ContractTypeResource.getByCode");
        log.info("code = " + code);

        ContractTypeDefinitionDTO dto = contractTypeService.findByCode(code);
        return Response.ok(dto).build();
    }

    /**
     * Get a contract type with all its pricing rules.
     *
     * @param code The contract type code
     * @return Contract type with rules DTO
     */
    @GET
    @Path("/{code}/with-rules")
    @Operation(summary = "Get contract type with rules", description = "Returns a contract type with all its pricing rules")
    @APIResponse(responseCode = "200", description = "Contract type with rules found")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response getWithRules(@Parameter(description = "Contract type code") @PathParam("code") String code) {
        log.info("ContractTypeResource.getWithRules");
        log.info("code = " + code);

        ContractTypeDefinitionDTO contractType = contractTypeService.findByCode(code);
        var rules = pricingRuleService.getRulesForContractType(code, true);

        ContractTypeWithRulesDTO result = new ContractTypeWithRulesDTO(contractType, rules);
        return Response.ok(result).build();
    }

    /**
     * Get a contract type with ALL its rules (pricing, validation, rate adjustments).
     *
     * @param code The contract type code
     * @return Contract type with all rules DTO
     */
    @GET
    @Path("/{code}/all-rules")
    @Operation(summary = "Get contract type with all rules",
               description = "Returns a contract type with pricing rules, validation rules, and rate adjustments")
    @APIResponse(responseCode = "200", description = "Contract type with all rules found")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response getAllRules(@Parameter(description = "Contract type code") @PathParam("code") String code) {
        log.info("ContractTypeResource.getAllRules");
        log.info("code = " + code);

        ContractTypeDefinitionDTO contractType = contractTypeService.findByCode(code);
        var pricingRules = pricingRuleService.getRulesForContractType(code, true);
        var validationRules = validationRuleService.listAll(code, false);
        var rateAdjustments = rateAdjustmentService.listAll(code, false);

        ContractTypeWithAllRulesDTO result = new ContractTypeWithAllRulesDTO(
            contractType,
            pricingRules,
            validationRules,
            rateAdjustments
        );
        return Response.ok(result).build();
    }

    /**
     * Create a new contract type.
     *
     * @param request The contract type data
     * @return Created contract type DTO
     */
    @POST
    @Operation(summary = "Create contract type", description = "Creates a new contract type definition")
    @APIResponse(responseCode = "201", description = "Contract type created")
    @APIResponse(responseCode = "400", description = "Invalid request or duplicate code")
    public Response create(@Valid CreateContractTypeRequest request) {
        log.info("ContractTypeResource.create");
        log.info("request = " + request);

        ContractTypeDefinitionDTO created = contractTypeService.create(request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    /**
     * Update an existing contract type.
     *
     * @param code The contract type code
     * @param request The updated data
     * @return Updated contract type DTO
     */
    @PUT
    @Path("/{code}")
    @Operation(summary = "Update contract type", description = "Updates an existing contract type definition")
    @APIResponse(responseCode = "200", description = "Contract type updated")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response update(
            @Parameter(description = "Contract type code") @PathParam("code") String code,
            @Valid UpdateContractTypeRequest request) {
        log.info("ContractTypeResource.update");
        log.info("code = " + code + ", request = " + request);

        ContractTypeDefinitionDTO updated = contractTypeService.update(code, request);
        return Response.ok(updated).build();
    }

    /**
     * Soft delete a contract type.
     * Sets active=false but preserves the record.
     *
     * @param code The contract type code
     * @return 204 No Content
     */
    @DELETE
    @Path("/{code}")
    @Operation(summary = "Delete contract type", description = "Soft deletes a contract type (sets active=false)")
    @APIResponse(responseCode = "204", description = "Contract type deleted")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    @APIResponse(responseCode = "400", description = "Contract type has active rules")
    public Response delete(@Parameter(description = "Contract type code") @PathParam("code") String code) {
        log.info("ContractTypeResource.delete");
        log.info("code = " + code);

        contractTypeService.softDelete(code);
        return Response.noContent().build();
    }

    /**
     * Reactivate a soft-deleted contract type.
     *
     * @param code The contract type code
     * @return 204 No Content
     */
    @POST
    @Path("/{code}/activate")
    @Operation(summary = "Activate contract type", description = "Reactivates a soft-deleted contract type")
    @APIResponse(responseCode = "204", description = "Contract type activated")
    @APIResponse(responseCode = "404", description = "Contract type not found")
    public Response activate(@Parameter(description = "Contract type code") @PathParam("code") String code) {
        log.info("ContractTypeResource.activate");
        log.info("code = " + code);

        contractTypeService.activate(code);
        return Response.noContent().build();
    }
}
