package dk.trustworks.intranet.apigateway.resources.gate;


import dk.trustworks.intranet.apigateway.dto.PublicUser;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.resources.NewsService;
import dk.trustworks.intranet.userservice.model.Employee;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import java.util.List;
import java.util.stream.Stream;

@JBossLog
@Path("/public")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"USER", "APPLICATION"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class PublicResource {

    @Inject
    ClientService clientAPI;

    @Inject
    NewsService newsService;

    @Inject
    PhotoService photoAPI;

    @Inject
    SlackService slackService;

    @GET
    @Path("/clients")
    public List<Client> findAllClients() {
        return clientAPI.listAllClients();
    }

    @GET
    @Path("/users")
    public List<PublicUser> findAllUsers() {
        Stream<Employee> stream = Employee.stream("status not in (?1, ?2)", StatusType.TERMINATED, StatusType.PREBOARDING);
        return stream.map(employee -> {
            PublicUser publicUser = new PublicUser();
            publicUser.setUuid(employee.getUuid());
            publicUser.setActive(!(employee.getStatus().equals(StatusType.TERMINATED) || employee.getStatus().equals(StatusType.PREBOARDING)));
            publicUser.setType(employee.getConsultanttype());
            publicUser.setFirstName(employee.getFirstname());
            publicUser.setLastName(employee.getLastname());
            publicUser.setBirthday(DateUtils.getNextBirthday(employee.getBirthday()));
            return publicUser;
        }).toList();
    }

    @GET
    @Path("/news/{category}")
    public List<News> findAllNews(@PathParam("category") String category) {
        return newsService.getActiveNews(category);
    }

    @GET
    @Path("/files/photos/{relateduuid}")
    public File findPhotoByRelatedUUID(@PathParam("relateduuid") String relateduuid) {
        return photoAPI.findPhotoByRelatedUUID(relateduuid);
    }

    @GET
    @Path("/users/{useruuid}/photo")
    public File findPhotoByUserUUID(@PathParam("useruuid") String useruuid) {
        return photoAPI.findPhotoByRelatedUUID(useruuid);
    }

    @POST
    @Path("/messaging/slack/message")
    @Operation(summary = "Send Slack message", description = "Sends a message to Slack")
    @RequestBody(
            description = "KeyValueDTO object containing the key (Slack channel name) and value (the message) for the Slack message",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = KeyValueDTO.class),
                    examples = @ExampleObject(
                            name = "SlackMessageExample",
                            summary = "Example Slack message",
                            value = "{\"key\": \"channel\", \"value\": \"Hello, this is a test message!\"}"
                    )
            )
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Message sent successfully"),
            @APIResponse(responseCode = "400", description = "Bad request"),
            @APIResponse(responseCode = "401", description = "Unauthorized"),
            @APIResponse(responseCode = "403", description = "Forbidden"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public void sendSlackMessage(KeyValueDTO keyValueDTO) {
        slackService.sendMessage(keyValueDTO.getKey(), keyValueDTO.getValue());
    }
}
