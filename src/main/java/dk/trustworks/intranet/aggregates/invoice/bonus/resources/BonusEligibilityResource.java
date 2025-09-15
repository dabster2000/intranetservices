package dk.trustworks.intranet.aggregates.invoice.bonus.resources;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "invoice-eligibility", description = "Whitelist for self-assign bonus. Én eligibility per bruger per finansår (FY). Selv-tilføjelse valideres mod fakturaens FY.")
@Path("/invoices/eligibility")
@RequestScoped
@SecurityRequirement(name = "jwt")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"}) // adjust as needed
public class BonusEligibilityResource {

    @Inject InvoiceBonusService service;

    public record EligibilityDTO(
            @Schema(description = "User UUID") String useruuid,
            @Schema(description = "May self-assign?") boolean canSelfAssign,
            @JsonProperty("groupuuid")
            @Schema(description = "Required: Target BonusEligibilityGroup UUID (one eligibility per financial year)")
            String groupUuid
    ) {
        @Override public String toString() {
            return "EligibilityDTO{useruuid='%s', canSelfAssign=%s, groupUuid='%s'}"
                    .formatted(useruuid, canSelfAssign, groupUuid);
        }
    }

    @GET
    @Operation(
            summary = "List eligibility entries",
            description = "Lists eligibility rows. Optional filters: useruuid and financialYear. One eligibility per user per FY."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "OK – list of eligibilities",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BonusEligibility[].class),
                            examples = {
                                    @ExampleObject(name = "All", value = "[{ \"uuid\": \"...\", \"useruuid\": \"11111111-1111-1111-1111-111111111111\", \"financialYear\": 2025, \"canSelfAssign\": true, \"group\": {\"uuid\": \"44444444-4444-4444-4444-444444444444\", \"name\": \"FY2025\", \"financialYear\": 2025}}]"),
                                    @ExampleObject(name = "Filtered", value = "[{ \"uuid\": \"...\", \"useruuid\": \"11111111-1111-1111-1111-111111111111\", \"financialYear\": 2026, \"canSelfAssign\": false, \"group\": {\"uuid\": \"55555555-5555-5555-5555-555555555555\", \"name\": \"FY2026\", \"financialYear\": 2026}}]")
                            }
                    )
            ),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public List<BonusEligibility> listEligibility(
            @Parameter(
                    name = "useruuid",
                    description = "Optional filter: only eligibilities for this user",
                    example = "11111111-1111-1111-1111-111111111111"
            ) @QueryParam("useruuid") String useruuid,
            @Parameter(
                    name = "financialYear",
                    description = "Optional filter: financial year starting year (FY YYYY starts on July 1 of YYYY)",
                    example = "2025"
            ) @QueryParam("financialYear") Integer financialYear) {
        return service.listEligibility(useruuid, financialYear);
    }

    @POST
    @Transactional
    @Operation(
            summary = "Upsert eligibility",
            description = "Creates or updates a user's eligibility for the financial year of the provided group (one per FY). If an eligibility already exists for (user, FY), it is updated to the new group and canSelfAssign value. groupUuid is required."
    )
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Created/Updated",
                    content = @Content(schema = @Schema(implementation = BonusEligibility.class),
                            examples = @ExampleObject(value = "{ \"uuid\": \"...\", \"useruuid\": \"11111111-1111-1111-1111-111111111111\", \"financialYear\": 2025, \"canSelfAssign\": true, \"group\": {\"uuid\": \"44444444-4444-4444-4444-444444444444\", \"name\": \"FY2025\", \"financialYear\": 2025}}"))),
            @APIResponse(responseCode = "400", description = "Bad request – missing groupUuid or group does not exist")
    })
    public Response upsertEligibility(
            @RequestBody(required = true, description = "Eligibility upsert payload",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = EligibilityDTO.class),
                            examples = @ExampleObject(name = "Create/Update",
                                    value = "{\n  \"useruuid\": \"11111111-1111-1111-1111-111111111111\",\n  \"canSelfAssign\": true,\n  \"groupuuid\": \"44444444-4444-4444-4444-444444444444\"\n}")))
            EligibilityDTO dto) {
        System.out.println("dto = " + dto);
        if (dto == null || dto.useruuid() == null || dto.useruuid().isBlank()) {
            throw new BadRequestException("useruuid is required");
        }
        if (dto.groupUuid() == null || dto.groupUuid().isBlank()) {
            throw new BadRequestException("groupUuid is required");
        }
        BonusEligibility be = service.upsertEligibility(
                dto.useruuid(),
                dto.canSelfAssign(),
                dto.groupUuid()
        );
        return Response.status(Response.Status.CREATED).entity(be).build();
    }

    @DELETE
    @Path("/{useruuid}")
    @Transactional
    @Operation(summary = "Delete eligibility",
               description = "Deletes all eligibility rows for the given user across financial years")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Deleted"),
            @APIResponse(responseCode = "404", description = "Not found")
    })
    public void deleteEligibility(
            @Parameter(name = "useruuid", description = "User UUID whose eligibilities will be deleted", example = "11111111-1111-1111-1111-111111111111")
            @PathParam("useruuid") String useruuid) {
        service.deleteEligibilityByUseruuid(useruuid);
    }
}
