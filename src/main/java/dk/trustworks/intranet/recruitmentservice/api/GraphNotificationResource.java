package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.application.integration.GraphNotificationProcessor;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Public Microsoft Graph webhook endpoint.
 *
 * <p>This is the ONE recruitment endpoint that is intentionally public —
 * Microsoft Graph cannot present a JWT, the contract is a shared
 * {@code clientState} secret carried in the body. Defence-in-depth:
 *
 * <ul>
 *   <li>Body-level constant-time {@code clientState} compare in
 *       {@link GraphNotificationProcessor};</li>
 *   <li>Always return 200 within ~30s — Graph treats anything else as a delivery
 *       failure and will retry until the subscription expires;</li>
 *   <li>Daily {@code GraphReconciliationWorker} is the safety net for any
 *       notification we silently drop (bad clientState, parser failure, etc.).</li>
 * </ul>
 *
 * <p>Validation handshake: when Graph creates/renews a subscription it does a
 * GET-style POST with a {@code validationToken} query parameter; we MUST echo
 * that token back as {@code text/plain} within 10s.
 */
@Path("/api/recruitment/integrations/graph")
@RequestScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.TEXT_PLAIN)
public class GraphNotificationResource {

    @Inject GraphNotificationProcessor processor;

    @POST
    @Path("/notifications")
    @PermitAll
    public Response notify(@QueryParam("validationToken") String validationToken, String body) {
        if (validationToken != null && !validationToken.isBlank()) {
            // Validation handshake: must echo the token verbatim as text/plain.
            return Response.ok(validationToken, MediaType.TEXT_PLAIN).build();
        }
        processor.process(body);
        return Response.ok().build();
    }
}
