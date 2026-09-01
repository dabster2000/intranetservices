package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.model.CompanyFactEntity;
import dk.trustworks.intranet.documentservice.model.enums.CompanyFactKey;
import dk.trustworks.intranet.documentservice.security.TemplateAccessPolicy;
import dk.trustworks.intranet.documentservice.services.CompanyFactService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST API for the per-company fact store (template-clauses spec §4.9).
 * <p>
 * Rides the template-admin posture: the service JWT proves the BFF may
 * call, {@code X-Requested-By} carries the human, and every endpoint —
 * reads included — requires HR/ADMIN via {@link TemplateAccessPolicy}.
 * Preparers never read raw facts; they see resolved values through the
 * prefill endpoint and rendered documents.
 */
@JBossLog
@Tag(name = "company-facts", description = "Per-company facts resolved by COMPANY template placeholders")
@Path("/company-facts")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"documents:read"})
public class CompanyFactResource {

    @Inject
    CompanyFactService companyFactService;

    @Inject
    TemplateAccessPolicy templateAccessPolicy;

    /** Upsert request body: one fact value for one company. */
    public record UpsertFactRequest(String factKey, String factValue) {
    }

    /** One entry of the seeded fact vocabulary, for the Settings editor. */
    public record FactKeyInfo(String key, String label) {
    }

    @GET
    @Operation(summary = "List all company facts (HR/ADMIN)")
    public List<CompanyFactEntity> listAll() {
        templateAccessPolicy.requireManager();
        return companyFactService.findAll();
    }

    @GET
    @Path("/keys")
    @Operation(summary = "The seeded fact-key vocabulary with Danish labels")
    public List<FactKeyInfo> keys() {
        templateAccessPolicy.requireManager();
        return CompanyFactKey.seeded().stream()
                .map(key -> new FactKeyInfo(key.name(), key.danishLabel()))
                .toList();
    }

    @GET
    @Path("/{companyUuid}")
    @Operation(summary = "List one company's facts (HR/ADMIN)")
    public List<CompanyFactEntity> listForCompany(@PathParam("companyUuid") String companyUuid) {
        templateAccessPolicy.requireManager();
        return companyFactService.findByCompany(companyUuid);
    }

    @PUT
    @Path("/{companyUuid}")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Create or update one fact for a company (HR/ADMIN, audit-logged)")
    public CompanyFactEntity upsert(@PathParam("companyUuid") String companyUuid, UpsertFactRequest request) {
        templateAccessPolicy.requireManager();
        if (request == null) {
            throw new WebApplicationException("Request body is required", 400);
        }
        return companyFactService.upsert(companyUuid, request.factKey(), request.factValue());
    }

    @DELETE
    @Path("/{companyUuid}/{factKey}")
    @RolesAllowed({"documents:write"})
    @Operation(summary = "Delete one fact for a company (HR/ADMIN, audit-logged)")
    public Response delete(@PathParam("companyUuid") String companyUuid, @PathParam("factKey") String factKey) {
        templateAccessPolicy.requireManager();
        companyFactService.delete(companyUuid, factKey);
        return Response.noContent().build();
    }
}
