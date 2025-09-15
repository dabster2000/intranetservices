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
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "invoice-eligibility", description = "Whitelist: who can self-assign bonuses")
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
            @Schema(description = "Active from (inclusive)") LocalDate activeFrom,
            @Schema(description = "Active to (inclusive)") LocalDate activeTo,
            @JsonProperty("groupuuid")
            //@JsonAlias({"groupuuid", "group_uuid"})
            @Schema(description = "Optional: Target BonusEligibilityGroup UUID to assign")
            String groupUuid
    ) {
        @Override public String toString() { /* uændret eller som før */
            return "EligibilityDTO{useruuid='%s', canSelfAssign=%s, activeFrom=%s, activeTo=%s, groupUuid='%s'}"
                    .formatted(useruuid, canSelfAssign, activeFrom, activeTo, groupUuid);
        }
    }

    @GET
    @Operation(summary = "List eligibility entries",
               description = "Lists all rows in invoice_bonus_eligibility")
    public List<BonusEligibility> listEligibility() {
        return service.listEligibility();
    }

    @POST
    @Transactional
    @Operation(summary = "Upsert eligibility",
               description = "Create or update an eligibility row for a user")
    public Response upsertEligibility(EligibilityDTO dto) {
        System.out.println("dto = " + dto);
        if (dto == null || dto.useruuid() == null || dto.useruuid().isBlank()) {
            throw new BadRequestException("useruuid is required");
        }
        BonusEligibility be = service.upsertEligibility(
                dto.useruuid(),
                dto.canSelfAssign(),
                dto.activeFrom(),
                dto.activeTo(),
                dto.groupUuid()
        );
        return Response.status(Response.Status.CREATED).entity(be).build();
    }

    @DELETE
    @Path("/{useruuid}")
    @Transactional
    @Operation(summary = "Delete eligibility",
               description = "Deletes the eligibility row for the given user")
    public void deleteEligibility(@PathParam("useruuid") String useruuid) {
        service.deleteEligibilityByUseruuid(useruuid);
    }
}
