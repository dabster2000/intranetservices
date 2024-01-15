package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jbosslog.JBossLog;

import static jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@RequestScoped
@PermitAll
public class SlackResource {

    @Inject
    SlackService slackAPI;

    @Blocking
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @Path("/slack/events")
    @POST
    public String challenge(RoutingContext rc) {
        log.debug("SlackResource.challenge");
        log.debug("payload = " + rc.getBodyAsString());
        String signature = rc.request().getHeader("X-Slack-Signature");
        String timestamp = rc.request().getHeader("X-Slack-Request-Timestamp");
        log.debug("signature = " + signature);
        log.debug("timestamp = " + timestamp);
        //slackAPI.sendMessage(signature, timestamp, rc.getBodyAsString());
        return "";
    }

    @Blocking
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_FORM_URLENCODED)
    @Path("/slack/action")
    @POST
    public String challenge2(RoutingContext rc) {
        String signature = rc.request().getHeader("X-Slack-Signature");
        String timestamp = rc.request().getHeader("X-Slack-Request-Timestamp");
        return "";//slackAPI.sendOtherMessage(signature, timestamp, rc.getBodyAsString());

    }
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class ChallengeRequest {
    private String token;
    private String challenge;
    private String type;
}