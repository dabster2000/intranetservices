package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.communicationsservice.dto.NewLeadNotificationDTO;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Outbound Slack notifications triggered by intranet features.
 * <p>
 * The dead JWT-locked {@code POST /slack/events} and
 * {@code POST /slack/action} stubs (never functional — Slack cannot send
 * a Trustworks JWT) were removed in recruitment ATS P13 together with
 * their unused {@code ChallengeRequest} DTO. Inbound Slack traffic now
 * has a real, signature-verified path: the BFF's
 * {@code /api/slack/interactions|commands|events} routes forwarding to
 * {@code POST /recruitment/slack/inbound} (Slack spec §4.2).
 */
@JBossLog
@RequestScoped
@Path("/slack")
@RolesAllowed({"notifications:write"})
@SecurityRequirement(name = "jwt")
public class SlackResource {

    @Inject
    SlackService slackAPI;

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
    @RolesAllowed({"notifications:write"})
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
