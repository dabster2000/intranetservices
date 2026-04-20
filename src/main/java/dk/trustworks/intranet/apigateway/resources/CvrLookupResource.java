package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.crm.client.CvrApiResponse;
import dk.trustworks.intranet.dao.crm.services.CvrLookupService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST resource for CVR (Danish company registry) lookups.
 *
 * <p>Proxies requests to the Virkdata API (virkdata.dk) via {@link CvrLookupService}.
 * All errors from the external API are mapped to appropriate HTTP status codes.
 * CVR API failures never block client creation — this is a convenience lookup.
 *
 * <p>Lookups by CVR number are cached to conserve the subscription quota.
 */
@Tag(name = "crm")
@Path("/cvr")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"crm:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class CvrLookupResource {

    @Inject
    CvrLookupService cvrLookupService;

    @GET
    @Path("/lookup")
    @Operation(
            summary = "Look up a company by CVR number",
            description = "Queries the Danish CVR registry (virkdata.dk) for company data by CVR number. " +
                    "Results are cached to conserve the subscription quota. " +
                    "If the CVR API is unavailable, returns 502 — the caller should allow manual entry."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Company found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = CvrApiResponse.class)
                    )
            ),
            @APIResponse(responseCode = "400", description = "Invalid CVR number format (must be 8 digits)"),
            @APIResponse(responseCode = "404", description = "No company found for the given CVR number"),
            @APIResponse(responseCode = "429", description = "CVR API daily quota exceeded"),
            @APIResponse(responseCode = "502", description = "CVR API is unavailable (BANNED, INTERNAL_ERROR, or connection failure)")
    })
    public Response lookup(
            @Parameter(description = "CVR number (8-digit Danish company registration number)", required = true, example = "25674114")
            @QueryParam("vat") String vat,
            @Parameter(description = "Country code", example = "dk")
            @QueryParam("country") @DefaultValue("dk") String country) {
        CvrApiResponse result = cvrLookupService.lookupByCvr(vat, country);
        return Response.ok(result).build();
    }

    @GET
    @Path("/search")
    @Operation(
            summary = "Search for a company by name",
            description = "Queries the Danish CVR registry (virkdata.dk) for company data by name. " +
                    "Returns the best match. Not cached because name searches are non-deterministic. " +
                    "If the CVR API is unavailable, returns 502 — the caller should allow manual entry."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Company found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = CvrApiResponse.class)
                    )
            ),
            @APIResponse(responseCode = "400", description = "Company name is required"),
            @APIResponse(responseCode = "404", description = "No company found matching the given name"),
            @APIResponse(responseCode = "429", description = "CVR API daily quota exceeded"),
            @APIResponse(responseCode = "502", description = "CVR API is unavailable")
    })
    public Response search(
            @Parameter(description = "Company name to search for", required = true, example = "Trustworks")
            @QueryParam("name") String name,
            @Parameter(description = "Country code", example = "dk")
            @QueryParam("country") @DefaultValue("dk") String country) {
        CvrApiResponse result = cvrLookupService.searchByName(name, country);
        return Response.ok(result).build();
    }
}
