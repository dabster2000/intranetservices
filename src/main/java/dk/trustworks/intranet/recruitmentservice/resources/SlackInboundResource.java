package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.slack.SlackInboundDispatchService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Set;

/**
 * The BFF-forwarded inbound-Slack dispatch endpoint (P13, Slack spec
 * §4.2/§6.1): {@code POST /recruitment/slack/inbound}. NOT a public
 * endpoint — the internet-facing side is the BFF's three signed routes
 * ({@code /api/slack/interactions|commands|events}), which verify the
 * Slack signature against the raw body and forward a normalized
 * {@link SlackInboundRequest} here with the system service-account
 * token.
 *
 * <h3>Caller contract</h3>
 * <ul>
 *   <li>System token with {@code recruitment:admin} — a machine scope no
 *       interactive user role maps to; the BFF's client-credentials
 *       token carries it via the {@code admin:*} expansion.</li>
 *   <li>{@code X-Slack-Inbound-Source: bff} — an internal marker header
 *       (defense in depth on top of the scope; requests without it are
 *       404'd like any unknown resource, the module's existence-hiding
 *       convention).</li>
 * </ul>
 *
 * <p>All identity, idempotency and allowlist logic lives in
 * {@link SlackInboundDispatchService}; this resource only validates the
 * envelope shape (explicit checks — {@code @Valid} is inert in this
 * repo, findings §P4.9) and translates it. Responses are always 200
 * with a disposition — Slack-side semantics (200-to-stop-retries) are
 * the BFF's job.
 */
@RequestScoped
@Path("/recruitment/slack/inbound")
@RolesAllowed({"recruitment:admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SlackInboundResource {

    /** Internal marker the BFF sets on every forwarded call. */
    public static final String INBOUND_SOURCE_HEADER = "X-Slack-Inbound-Source";
    static final String INBOUND_SOURCE_BFF = "bff";

    private static final Set<String> SURFACES = Set.of("interactions", "commands", "events");

    @Inject
    SlackInboundDispatchService dispatchService;

    @POST
    public SlackInboundResponse dispatch(
            @HeaderParam(INBOUND_SOURCE_HEADER) String inboundSource,
            SlackInboundRequest request) {
        if (!INBOUND_SOURCE_BFF.equals(inboundSource)) {
            // Existence-hiding: without the internal marker this endpoint
            // does not exist (module convention: 404, not 403).
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        validate(request);
        return dispatchService.dispatch(request);
    }

    /** Explicit envelope validation — @Valid is inert in this repo. */
    private void validate(SlackInboundRequest request) {
        if (request == null) {
            throw badRequest("MISSING_BODY");
        }
        requireText(request.surface(), 20, "surface");
        if (!SURFACES.contains(request.surface())) {
            throw badRequest("INVALID_SURFACE");
        }
        requireText(request.payloadId(), 160, "payloadId");
        requireText(request.slackUserId(), 32, "slackUserId");
        requireText(request.kind(), 40, "kind");
        requireText(request.handlerKey(), 120, "handlerKey");
        optionalText(request.slackTeamId(), 32, "slackTeamId");
        optionalText(request.triggerId(), 80, "triggerId");
        optionalText(request.responseUrl(), 500, "responseUrl");
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw badRequest("INVALID_" + field.toUpperCase());
        }
    }

    private void optionalText(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw badRequest("INVALID_" + field.toUpperCase());
        }
    }

    private WebApplicationException badRequest(String code) {
        return new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + code + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }
}
