package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.knowledgeservice.model.Conference;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.codec.binary.Base64;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ConferenceService {

    @Inject
    MailResource mailResource;

    public List<Conference> findAllConferences() {
        log.debug("ConferenceService.findAllConferences");
        return Conference.listAll();
    }

    public Conference findConferenceBySlug(String slug) {
        log.debug("ConferenceService.findConferenceBySlug: {}", slug);
        return Conference.<Conference>find("slug", slug).stream().findAny().orElse(null);
    }

    @Transactional
    public void createConference(Conference conference) {
        log.info("ConferenceService.createConference: {}", conference.getName());
        conference.persist();
    }

    @Transactional
    public List<ConferenceParticipant> findAllConferenceParticipants(String conferenceuuid) {
        String hql = "SELECT a FROM ConferenceParticipant a " +
                "LEFT JOIN ConferenceParticipant b " +
                "ON a.participantuuid = b.participantuuid AND a.registered < b.registered " +
                "WHERE b.participantuuid IS NULL AND a.conferenceuuid = ?1";

        return ConferenceParticipant.find(hql, conferenceuuid).list();
    }


    /*
    public List<ConferenceParticipant> findAllConferenceParticipants(String conferenceuuid) {
        return ConferenceParticipant.list("conferenceuuid", conferenceuuid);
        /*
        return ConferenceParticipant.getEntityManager().createNativeQuery("SELECT a.* " +
                "FROM twservices.conference_participants a " +
                "LEFT JOIN twservices.conference_participants b " +
                "ON a.participantuuid = b.participantuuid AND a.registered < b.registered " +
                "WHERE b.participantuuid IS NULL and a.conferenceuuid like '"+conferenceuuid+"'", ConferenceParticipant.class).getResultList();

    }
         */


    @Transactional
    public void createParticipant(ConferenceParticipant conferenceParticipant) {
        ConferencePhase conferencePhase = conferenceParticipant.getConferencePhase();
        if(conferencePhase.isUseMail()) {
            mailResource.sendingMail(conferenceParticipant.getEmail(), conferencePhase.getSubject(), new String(Base64.decodeBase64(conferencePhase.getMail().getBytes())));
        }
        conferenceParticipant.persist();
    }

    @Transactional
    public void updateParticipantData(ConferenceParticipant conferenceParticipant) {
        conferenceParticipant.persist();
    }

    @Transactional
    public void changeParticipantPhase(ConferenceParticipant conferenceParticipant) {
        ConferencePhase conferencePhase = conferenceParticipant.getConferencePhase();
        if(conferencePhase.isUseMail()) {
            mailResource.sendingMail(conferenceParticipant.getEmail(), conferencePhase.getSubject(), new String(Base64.decodeBase64(conferencePhase.getMail().getBytes())));
        }
        conferenceParticipant.persist();
    }

    public ConferencePhase findConferencePhase(String conferenceUUID, int phase) {
        return (ConferencePhase) ConferencePhase.find("conferenceuuid = ?1 and step = ?2", conferenceUUID, phase).firstResultOptional().orElseThrow();
    }
}
