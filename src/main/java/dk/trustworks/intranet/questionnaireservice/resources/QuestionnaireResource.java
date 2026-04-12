package dk.trustworks.intranet.questionnaireservice.resources;

import dk.trustworks.intranet.questionnaireservice.dto.CreateQuestionnaireRequest;
import dk.trustworks.intranet.questionnaireservice.dto.CreateSubmissionRequest;
import dk.trustworks.intranet.questionnaireservice.dto.QuestionnaireStatsResponse;
import dk.trustworks.intranet.questionnaireservice.model.Questionnaire;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireSubmission;
import dk.trustworks.intranet.questionnaireservice.services.QuestionnaireService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "questionnaire")
@Path("/questionnaires")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"questionnaires:read"})
public class QuestionnaireResource {

    @Inject
    QuestionnaireService questionnaireService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @POST
    @RolesAllowed({"admin:*"})
    public Response createQuestionnaire(CreateQuestionnaireRequest request) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.infof("Create questionnaire requested by user=%s, title=%s", userUuid, request.getTitle());
        Questionnaire q = questionnaireService.createQuestionnaire(request);
        return Response.created(URI.create("/questionnaires/" + q.getUuid()))
                .entity(q)
                .build();
    }

    @GET
    public List<Questionnaire> listAll() {
        return questionnaireService.listAll();
    }

    @GET
    @Path("/{uuid}")
    public Questionnaire findByUuid(@PathParam("uuid") String uuid) {
        return questionnaireService.findByUuid(uuid);
    }

    @GET
    @Path("/{uuid}/submissions")
    public List<QuestionnaireSubmission> getSubmissions(@PathParam("uuid") String uuid) {
        return questionnaireService.getSubmissions(uuid);
    }

    @GET
    @Path("/{uuid}/submissions/stats")
    public QuestionnaireStatsResponse getStats(@PathParam("uuid") String uuid) {
        return questionnaireService.getStats(uuid);
    }

    @GET
    @Path("/{uuid}/submissions/mine")
    public List<QuestionnaireSubmission> getMySubmissions(@PathParam("uuid") String uuid) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.infof("Get my submissions: questionnaire=%s, user=%s", uuid, userUuid);
        return questionnaireService.getMySubmissions(uuid, userUuid);
    }

    @GET
    @Path("/active-reminders")
    public List<Questionnaire> getActiveReminders() {
        String userUuid = requestHeaderHolder.getUserUuid();
        return questionnaireService.getActiveReminders(userUuid);
    }

    @POST
    @Path("/{uuid}/submissions")
    @RolesAllowed({"questionnaires:write"})
    public Response createSubmission(@PathParam("uuid") String uuid, CreateSubmissionRequest request) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.infof("Create submission requested: questionnaire=%s, client=%s, user=%s",
                uuid, request.getClientUuid(), userUuid);
        QuestionnaireSubmission submission = questionnaireService.createSubmission(uuid, userUuid, request);
        return Response.created(URI.create("/questionnaires/" + uuid + "/submissions/" + submission.getUuid()))
                .entity(submission)
                .build();
    }
}
