package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.conference.events.ChangeParticipantPhaseEvent;
import dk.trustworks.intranet.aggregates.conference.events.CreateParticipantEvent;
import dk.trustworks.intranet.aggregates.conference.events.UpdateParticipantDataEvent;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceService;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.knowledgeservice.model.Conference;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@JBossLog
@Tag(name = "Conference")
@Path("/knowledge/conferences")
@RequestScoped
public class ConferenceResource {

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    ConferenceService conferenceService;

    @Inject
    MailResource mailResource;

    @GET
    public List<Conference> findAllConferences() {
        return conferenceService.findAllConferences();
    }

    @POST
    public void createConference(Conference conference) {
        conferenceService.createConference(conference);
    }

    @GET
    @Path("/{conferenceuuid}/participants")
    public List<ConferenceParticipant> findAllConferenceParticipants(@PathParam("conferenceuuid") String conferenceuuid) {
        System.out.println("ConferenceResource.findAllConferenceParticipants");
        System.out.println("conferenceuuid = " + conferenceuuid);
        return conferenceService.findAllConferenceParticipants(conferenceuuid);
    }

    @GET
    @Path("/{conferenceuuid}/phases")
    public List<ConferencePhase> findAllConferencePhases(@PathParam("conferenceuuid") String conferenceuuid) {
        List<ConferencePhase> list = ConferencePhase.list("conferenceuuid", conferenceuuid);
        return list.stream().sorted(Comparator.comparing(ConferencePhase::getStep)).toList();
    }

    @POST
    @Path("/{conferenceuuid}/phases")
    @Transactional
    public void addConferencePhase(@PathParam("conferenceuuid") String conferenceuuid, ConferencePhase conferencePhase) {
        System.out.println("ConferenceResource.addConferencePhase");
        System.out.println("conferenceuuid = " + conferenceuuid + ", conferencePhase = " + conferencePhase);
        if(conferencePhase.getUuid()==null) throw new IllegalArgumentException("ConferencePhase must have a uuid");
        ConferencePhase.findByIdOptional(conferencePhase.getUuid()).ifPresentOrElse(cp -> updatePhase(conferencePhase), conferencePhase::persist);
    }

    private void updatePhase(ConferencePhase conferencePhase) {
        System.out.println("ConferenceResource.updatePhase");
        ConferencePhase.update("step = ?1, name = ?2, useMail = ?3, subject = ?4, mail = ?5 where uuid = ?6",
                conferencePhase.getStep(), conferencePhase.getName(), conferencePhase.isUseMail(), conferencePhase.getSubject(), conferencePhase.getMail(), conferencePhase.getUuid());
    }

    @DELETE
    @Path("/{conferenceuuid}/phases/{phaseuuid}")
    @Transactional
    public void deleteConferencePhase(@PathParam("conferenceuuid") String conferenceuuid, @PathParam("phaseuuid") String phaseuuid) {
        ConferencePhase.delete("uuid", phaseuuid);
    }

    @POST
    @PermitAll
    @Path("/{conferenceuuid}/participants")
    public void createParticipant(@PathParam("conferenceuuid") String conferenceUUID, ConferenceParticipant conferenceParticipant) {
        createParticipant(conferenceUUID, 0, conferenceParticipant);
    }

    @POST
    @PermitAll
    @Path("/{conferenceuuid}/phase/{phasenumber}/participants")
    public void createParticipant(@PathParam("conferenceuuid") String conferenceUUID, @PathParam("phasenumber") int phaseNumber, ConferenceParticipant conferenceParticipant) {
        System.out.println("ConferenceResource.createParticipant");
        System.out.println("conferenceUUID = " + conferenceUUID + ", phaseNumber = " + phaseNumber + ", conferenceParticipant = " + conferenceParticipant);
        conferenceParticipant.setRegistered(LocalDateTime.now());
        conferenceParticipant.setUuid(UUID.randomUUID().toString());
        conferenceParticipant.setConferenceuuid(conferenceUUID);
        conferenceParticipant.setParticipantuuid(UUID.randomUUID().toString());

        conferenceParticipant.setConferencePhase(conferenceService.findConferencePhase(conferenceUUID, phaseNumber));

        CreateParticipantEvent event = new CreateParticipantEvent(conferenceUUID, conferenceParticipant);
        aggregateEventSender.handleEvent(event);
    }

    @PUT
    @Path("/{conferenceuuid}/participants")
    public void updateParticipantData(@PathParam("conferenceuuid") String conferenceUUID, ConferenceParticipant conferenceParticipant) {
        conferenceParticipant.setRegistered(LocalDateTime.now());
        conferenceParticipant.setUuid(UUID.randomUUID().toString());
        conferenceParticipant.setConferenceuuid(conferenceUUID);
        UpdateParticipantDataEvent event = new UpdateParticipantDataEvent(conferenceUUID, conferenceParticipant);
        aggregateEventSender.handleEvent(event);
    }

    @POST
    @Path("/{conferenceuuid}/phase/{phasenumber}/participants/list")
    public void changeParticipantPhase(@PathParam("conferenceuuid") String conferenceUUID, @PathParam("phasenumber") int phaseNumber, List<ConferenceParticipant> conferenceParticipantList) {
        conferenceParticipantList.forEach(conferenceParticipant -> {
            conferenceParticipant.setRegistered(LocalDateTime.now());
            conferenceParticipant.setUuid(UUID.randomUUID().toString());
            conferenceParticipant.setConferenceuuid(conferenceUUID);
            conferenceParticipant.setConferencePhase(conferenceService.findConferencePhase(conferenceUUID, phaseNumber));
            ChangeParticipantPhaseEvent event = new ChangeParticipantPhaseEvent(conferenceUUID, conferenceParticipant);
            aggregateEventSender.handleEvent(event);
        });
    }

    @POST
    @Path("/apply/forefront2024")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm2(@FormParam("name") String name, @FormParam("company") String company, @FormParam("titel") String titel,
                            @FormParam("email") String email, @FormParam("andet") String andet, @FormParam("samtykke[0]") String samtykke) {
        createParticipant("04e9bd12-4800-4780-ada9-dfe8ddd05a9d", new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke)));
    }

    @POST
    @Path("/{conferenceuuid}/apply")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public void receiveForm(@PathParam("conferenceuuid") String conferenceuuid, @FormParam("name") String name, @FormParam("company") String company, @FormParam("titel") String titel,
                            @FormParam("email") String email, @FormParam("andet") String andet, @FormParam("samtykke[0]") String samtykke) {
        createParticipant(conferenceuuid, new ConferenceParticipant(name, company, titel, email, andet, "ja".equals(samtykke)));
    }

    @POST
    @Path("/message")
    public void message(TrustworksMail mail) {
        mailResource.sendingHTML(mail);
    }
}