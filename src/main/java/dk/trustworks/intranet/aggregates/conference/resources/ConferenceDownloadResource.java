package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceDownloadService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/knowledge/conferences/{conferenceuuid}/downloads")
@RequestScoped
@RolesAllowed("conference:read")
@Tag(name = "Conference")
public class ConferenceDownloadResource {
    @Inject
    ConferenceDownloadService service;

    @POST
    @Path("/{presentationId}")
    @PermitAll
    @Operation(summary = "Count one anonymous presentation download request",
            description = "Accepts only the five Cloud & AI Festival presentation IDs. "
                    + "Records an aggregate request count, not a completed transfer or an individual visitor.")
    public Response recordRequest(@PathParam("conferenceuuid") String conferenceUuid,
                                  @PathParam("presentationId") String presentationId) {
        service.recordRequest(conferenceUuid, presentationId);
        return Response.noContent().header("Cache-Control", "no-store").build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read aggregate presentation download request counts")
    public Response counts(@PathParam("conferenceuuid") String conferenceUuid) {
        return Response.ok(service.counts(conferenceUuid))
                .header("Cache-Control", "no-store").build();
    }
}
