package dk.trustworks.intranet.communicationsservice.resources;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.services.UserService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.io.IOException;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Path("/communications/slackmessage")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class SlackResource {

    @ConfigProperty(name = "slack.motherSlackBotToken")
    String motherSlackBotToken;

    @Inject
    UserService userService;

    @POST
    @Path("/users/{useruuid}/simple")
    public void sendMessage(@PathParam("useruuid") String useruuid, KeyValueDTO keyValueDTO) throws SlackApiException, IOException {
        Slack slack = Slack.getInstance();
        User user = userService.findById(useruuid, true);
        log.info("Sending message to "+user.getUsername());
        ChatPostMessageResponse response = slack.methods(motherSlackBotToken).chatPostMessage(req -> req
                .channel(user.getSlackusername()) // Channel ID
                .text(keyValueDTO.getValue()));
        log.info("Response received: " + response.getMessage());
    }
/*
    @POST
    @Path("/users/{useruuid}/rich")
    public void sendMessage(String useruuid, ChatPostMessageMethod textMessage) {
        SlackWebApiClient motherWebApiClient = SlackClientFactory.createWebApiClient(motherSlackBotToken);
        User user = userAPI.findById(useruuid, "true");
        log.info("Sending message to " + user.getUsername());
        motherWebApiClient.postMessage(textMessage);
    }

 */

}