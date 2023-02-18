package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceApplicationStatus;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceType;
import dk.trustworks.intranet.knowledgeservice.services.ConferenceService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

@JBossLog
@Tag(name = "Conference")
@Path("/knowledge/conferences")
@RequestScoped
public class ConferenceResource {

    @Inject
    ConferenceService conferenceService;

    @GET
    public List<ConferenceParticipant> findAll() {
        return ConferenceParticipant.findAll().list();
    }

    @POST
    @Path("/apply")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm(@FormParam("name")  String name, @FormParam("email") String email, @FormParam("company") String company) {
        conferenceService.addParticipant(new ConferenceParticipant(name, email, company,
                "", ConferenceType.CONFERENCE,
                ConferenceApplicationStatus.WAITING));
    }

}