package dk.trustworks.intranet.contracts.resources;

import dk.trustworks.intranet.contracts.audit.ContractTypeAuditService;
import dk.trustworks.intranet.contracts.dto.ContractTypeAuditResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
 * REST API for reading the framework agreement (contract type) audit trail.
 *
 * <p>Kept as a dedicated resource class (rather than a method on
 * {@link ContractTypeResource}) so the audit surface stays self-contained; the class-level
 * path embeds the sub-path, mirroring {@link PricingRuleResource}.
 */
@Tag(name = "Contract Types", description = "Manage dynamic contract type definitions")
@Path("/api/contract-types/{code}/audit")
@JBossLog
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"contracts:read"})
public class ContractTypeAuditResource {

    @Inject
    ContractTypeAuditService auditService;

    /**
     * Get the audit trail for a contract type (agreement + its pricing/validation rules),
     * newest first.
     *
     * @param code  the contract type code
     * @param limit maximum number of entries (default 100, max 500)
     * @return {@code {"entries":[...]}} — empty list for codes with no recorded changes
     */
    @GET
    @Operation(summary = "Get contract type audit trail",
               description = "Returns the audit trail (agreement, pricing rule, and validation rule mutations) "
                       + "for a contract type, newest first")
    @APIResponse(
        responseCode = "200",
        description = "Audit entries, newest first",
        content = @Content(schema = @Schema(implementation = ContractTypeAuditResponse.class))
    )
    public Response getAudit(
            @Parameter(description = "Contract type code") @PathParam("code") String code,
            @Parameter(description = "Maximum number of entries (default 100, max 500)")
            @QueryParam("limit") @DefaultValue("100") int limit) {
        log.debugf("Getting audit trail for contract type=%s, limit=%d", code, limit);

        ContractTypeAuditResponse response =
                new ContractTypeAuditResponse(auditService.getAuditForContractType(code, limit));
        return Response.ok(response).build();
    }
}
