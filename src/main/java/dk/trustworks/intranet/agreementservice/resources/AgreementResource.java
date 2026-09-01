package dk.trustworks.intranet.agreementservice.resources;

import dk.trustworks.intranet.agreementservice.dto.AgreementDTO;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.security.AgreementAccessPolicy;
import dk.trustworks.intranet.agreementservice.services.AgreementService;
import dk.trustworks.intranet.agreementservice.services.AgreementService.CreateAgreementRequest;
import dk.trustworks.intranet.agreementservice.services.AgreementService.UpdateAgreementRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The agreement registry API (template-clauses spec §8–§9, D5/D9).
 *
 * <p>Registry data is salary-adjacent: the dedicated
 * {@code agreements:read}/{@code agreements:write} scopes gate the BFF
 * client ({@code AdminScopeAugmentor} expands {@code admin:*}), and the
 * acting human must be HR/ADMIN via {@link AgreementAccessPolicy} on
 * every endpoint — registry rows never ride User responses.</p>
 */
@JBossLog
@Tag(name = "agreements", description = "Registry of signed negotiated terms per employee/candidate")
@Path("/agreements")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"agreements:read"})
public class AgreementResource {

    @Inject
    AgreementService agreementService;

    @Inject
    AgreementAccessPolicy accessPolicy;

    @GET
    @Operation(summary = "Filtered registry list (HR/ADMIN)")
    public List<AgreementDTO> find(@QueryParam("type") String type,
                                   @QueryParam("status") String status,
                                   @QueryParam("userUuid") String userUuid,
                                   @QueryParam("candidateUuid") String candidateUuid,
                                   @QueryParam("expiringBefore") String expiringBefore) {
        accessPolicy.requireManager();
        return agreementService.find(type, status, userUuid, candidateUuid,
                parseDate(expiringBefore));
    }

    @GET
    @Path("/types")
    @Operation(summary = "Agreement-type vocabulary (HR/ADMIN)")
    public List<AgreementType> getTypes() {
        accessPolicy.requireManager();
        return agreementService.findTypes();
    }

    @GET
    @Path("/{uuid}")
    @Operation(summary = "One registry row with parameters and wording version (HR/ADMIN)")
    public AgreementDTO getByUuid(@PathParam("uuid") String uuid) {
        accessPolicy.requireManager();
        return agreementService.findByUuid(uuid);
    }

    @POST
    @RolesAllowed({"agreements:write"})
    @Operation(summary = "Manual registry entry (HR/ADMIN); optionally supersedes a prior row")
    public Response create(CreateAgreementRequest request) {
        String actor = accessPolicy.requireManager();
        requireBody(request);
        log.infof("POST /agreements: user=%s type=%s", request.userUuid(), request.agreementType());
        return Response.status(Response.Status.CREATED)
                .entity(agreementService.create(request, actor)).build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"agreements:write"})
    @Operation(summary = "Edit a registry row (HR/ADMIN)")
    public AgreementDTO update(@PathParam("uuid") String uuid, UpdateAgreementRequest request) {
        String actor = accessPolicy.requireManager();
        requireBody(request);
        log.infof("PUT /agreements/%s", uuid);
        return agreementService.update(uuid, request, actor);
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"agreements:write"})
    @Operation(summary = "Delete a MANUAL registry row (HR/ADMIN); signed rows are ended by status")
    public Response delete(@PathParam("uuid") String uuid) {
        String actor = accessPolicy.requireManager();
        log.infof("DELETE /agreements/%s", uuid);
        agreementService.delete(uuid, actor);
        return Response.noContent().build();
    }

    private static void requireBody(Object body) {
        if (body == null) {
            throw new WebApplicationException("Request body is required", 400);
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new WebApplicationException("Invalid date (expected ISO yyyy-MM-dd): " + raw, 400);
        }
    }
}
