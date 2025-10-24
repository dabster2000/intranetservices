package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.communicationsservice.dto.NewLeadNotificationDTO;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import static jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@RequestScoped
@Path("/slack")
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class SlackResource {

    @Inject
    SlackService slackAPI;

    @Blocking
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @Path("/events")
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
    @Path("/action")
    @POST
    public String challenge2(RoutingContext rc) {
        String signature = rc.request().getHeader("X-Slack-Signature");
        String timestamp = rc.request().getHeader("X-Slack-Request-Timestamp");
        return "";//slackAPI.sendOtherMessage(signature, timestamp, rc.getBodyAsString());

    }

    /**
     * Sends a new lead notification to Slack.
     * This endpoint is called when a new sales lead is created in the system.
     *
     * @param dto The new lead notification data
     * @return HTTP 200 on success, 500 on error
     */
    @Blocking
    @POST
    @Path("/new-lead-notification")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @RolesAllowed({"SALES", "ADMIN", "SYSTEM"})
    public Response sendNewLeadNotification(NewLeadNotificationDTO dto) {
        log.info("Received new lead notification request for client: " + dto.getClientName());

        try {
            slackAPI.sendNewLeadNotification(dto);
            log.info("New lead notification sent successfully");
            return Response.ok()
                .entity("{\"success\": true, \"message\": \"Notification sent to Slack\"}")
                .build();
        } catch (Exception e) {
            log.error("Failed to send new lead notification: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"success\": false, \"error\": \"" + e.getMessage() + "\"}")
                .build();
        }
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