package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceApplicationStatus;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceType;
import dk.trustworks.intranet.knowledgeservice.services.ConferenceService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import java.util.Comparator;
import java.util.List;

@JBossLog
@Tag(name = "Conference")
@Path("/knowledge/conferences")
@RequestScoped
public class ConferenceResource {

    @Inject
    ConferenceService conferenceService;

    @Inject
    MailResource mailResource;

    @GET
    public List<ConferenceParticipant> findAll() {
        List<ConferenceParticipant> list = ConferenceParticipant.findAll().list();
        return list.stream().sorted(Comparator.comparing(ConferenceParticipant::getRegistered).reversed()).distinct().toList();
    }

    @POST
    @Path("/apply")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm(@FormParam("name") String name, @FormParam("company") String company, @FormParam("titel") String titel,
                            @FormParam("email") String email, @FormParam("andet") String andet, @FormParam("samtykke[0]") String samtykke, Form form) {
        conferenceService.addParticipant(new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke), ConferenceType.CONFERENCE,
                ConferenceApplicationStatus.WAITING));
    }

    @POST
    @Path("/invite")
    public void invite(List<ConferenceParticipant> participants) {
        conferenceService.inviteParticipants(participants);
    }

    @POST
    @Path("/deny")
    public void deny(List<ConferenceParticipant> participants) {
        conferenceService.denyParticipants(participants);
    }

    @POST
    @Path("/withdraw")
    public void withdraw(List<ConferenceParticipant> participants) {
        conferenceService.withdraw(participants);
    }

    @POST
    @Path("/message")
    public void message(TrustworksMail mail) {
        mailResource.sendingHTML(mail);
    }
}