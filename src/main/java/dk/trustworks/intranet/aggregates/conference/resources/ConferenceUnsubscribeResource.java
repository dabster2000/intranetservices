package dk.trustworks.intranet.aggregates.conference.resources;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceUnsubscribeService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Token capability AND service scope are required. No caller-supplied identity is accepted. */
@Path("/knowledge/conferences/unsubscribe")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("conference:read")
public class ConferenceUnsubscribeResource {
    static final int MAX_BODY_BYTES = 128;
    @Inject ConferenceUnsubscribeService service;
    @Inject ObjectMapper mapper;

    @GET
    public Response status(@HeaderParam(ConferenceUnsubscribeService.TOKEN_HEADER) String token) {
        return execute(() -> service.status(token));
    }

    @POST
    @RolesAllowed("conference:write")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response unsubscribe(@HeaderParam(ConferenceUnsubscribeService.TOKEN_HEADER) String token, InputStream body) {
        return execute(() -> {
            ConferenceUnsubscribeService.hashToken(token); // cheap bounded validation before parsing
            try {
                byte[] bytes = body == null ? new byte[0] : body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) throw ConferenceUnsubscribeService.invalid("INVALID_REQUEST");
                JsonNode request;
                try (var parser = mapper.createParser(bytes)) {
                    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                    request = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
                }
                if (request == null || !request.isObject() || request.size() != 1
                        || !request.path("action").isTextual() || !"UNSUBSCRIBE".equals(request.path("action").asText())) {
                    throw ConferenceUnsubscribeService.invalid("INVALID_REQUEST");
                }
            } catch (IOException e) {
                throw ConferenceUnsubscribeService.invalid("INVALID_REQUEST");
            }
            return service.unsubscribe(token);
        });
    }

    private Response execute(java.util.function.Supplier<ConferenceUnsubscribeService.PublicState> action) {
        try {
            return secure(Response.ok(action.get()));
        } catch (WebApplicationException e) {
            return secure(Response.fromResponse(e.getResponse()));
        } catch (RuntimeException e) {
            return secure(Response.status(503).entity(Map.of("error", "UNAVAILABLE")));
        }
    }

    private static Response secure(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("X-Content-Type-Options", "nosniff").build();
    }
}
