package dk.trustworks.intranet.apigateway.resources.gate;


import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.work.events.UpdateWorkEvent;
import dk.trustworks.intranet.apigateway.dto.PublicUser;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.crm.services.TaskService;
import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WeekService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.resources.NewsService;
import dk.trustworks.intranet.sales.model.SalesCoffeeDate;
import dk.trustworks.intranet.userservice.model.Employee;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;

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

    @Inject
    WeekService weekService;

    @Inject
    TaskService taskService;

    @Inject
    ProjectService projectService;

    @Inject
    WorkService workService;

    @Inject
    AggregateEventSender sender;

    @GET
    @Path("/clients")
    public List<Client> findAllClients() {
        return clientAPI.listAllClients();
    }

    @GET
    @Path("/users")
    public List<PublicUser> findAllUsers() {
        Stream<Employee> stream = Employee.stream(
                "status not in (?1, ?2) and (consultanttype is null or consultanttype <> ?3)",
                StatusType.TERMINATED, StatusType.PREBOARDING, ConsultantType.EXTERNAL
        );
        return stream.map(PublicUser::new).toList();
    }

    @GET
    @Path("/users/{useruuid}")
    public PublicUser findByUseruuid(@PathParam("useruuid") String useruuid) {
        Employee employee = Employee.findById(useruuid);
        if (employee == null) return null;
        return new PublicUser(employee);
    }

    @GET
    @Path("/users/{useruuid}/weeks/{year}/{weeknumber}")
    public List<Week> findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(@PathParam("useruuid") String useruuid, @PathParam("weeknumber") String strWeeknumber, @PathParam("year") String strYear) {
        int year = Integer.parseInt(strYear);
        int weeknumber = Integer.parseInt(strWeeknumber);
        return weekService.findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(weeknumber, year, useruuid);
    }

    @GET
    @Path("/news/{category}")
    public List<News> findAllNews(@PathParam("category") String category) {
        return newsService.getActiveNews(category);
    }

    @GET
    @Path("/files/photos/{relateduuid}")
    public File findPhotoByRelatedUUID(@PathParam("relateduuid") String relateduuid,
                                       @QueryParam("width") Integer width) {
        log.debug("Public photo request " + relateduuid + (width != null ? " width=" + width : ""));
        File photo = photoAPI.findPhotoByRelatedUUID(relateduuid);
        if (width != null) {
            photo.setFile(photoAPI.getResizedPhoto(relateduuid, width));
        }
        return photo;
    }

    @GET
    @Path("/users/{useruuid}/photo")
    public File findPhotoByUserUUID(@PathParam("useruuid") String useruuid,
                                    @QueryParam("width") Integer width) {
        log.debug("Public user photo request " + useruuid + (width != null ? " width=" + width : ""));
        File photo = photoAPI.findPhotoByRelatedUUID(useruuid);
        if (width != null) {
            photo.setFile(photoAPI.getResizedPhoto(useruuid, width));
        }
        return photo;
    }

    @GET
    @Path("/tasks/{uuid}")
    public Task findByTaskuuid(@PathParam("uuid") String uuid) {
        return taskService.findByUuid(uuid);
    }

    @GET
    @Path("/projects/{uuid}")
    @SecurityRequirement(name = "jwt", scopes = {})
    public Project findByProjectuuid(@PathParam("uuid") String uuid) {
        return projectService.findByUuid(uuid);
    }

    @GET
    @Path("/users/{uuid}/work")
    public List<WorkFull> getUserWorkByPeriod(@PathParam("uuid") String useruuid, @QueryParam("fromdate") Optional<String> fromDate, @QueryParam("todate") Optional<String> toDate) {
        return workService.findByPeriodAndUserUUID(dateIt(fromDate.orElse("2014-02-01")), dateIt(toDate.orElse(stringIt(LocalDate.now()))), useruuid);
    }

    @GET
    @Path("/coffeedates")
    public List<SalesCoffeeDate> getCoffeeDates() {
        List<SalesCoffeeDate> coffeeDateList = SalesCoffeeDate.<SalesCoffeeDate>listAll();
        coffeeDateList.forEach(coffeeDate -> {
            coffeeDate.addPublicUser(Employee.findById(coffeeDate.getUseruuid()));
        });
        return coffeeDateList;
    }


    @POST
    @Path("/work")
    public void save(Work work) {
        workService.persistOrUpdate(work);

        sender.handleEvent(new UpdateWorkEvent(work.getUseruuid(), work));
        if(work.getWorkas()!=null && !work.getWorkas().isEmpty())
            sender.handleEvent(new UpdateWorkEvent(work.getWorkas(), work));
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
