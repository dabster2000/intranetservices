package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.quarkus.vertx.web.Route;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jbosslog.JBossLog;

import javax.annotation.security.PermitAll;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;

import static io.quarkus.vertx.web.Route.HttpMethod.POST;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@RequestScoped
@PermitAll
public class SlackResource {

    @Inject
    SlackService slackAPI;

    @Blocking
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @Route(path = "/slack/events", methods = POST)
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
    @Route(path = "/slack/action", methods = POST)
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