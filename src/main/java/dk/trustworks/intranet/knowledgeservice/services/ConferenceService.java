package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ConferenceService {

    public List<ConferenceParticipant> findByConference(String conferenceuuid) {
        return ConferenceParticipant.find("conferenceuuid like ?1", conferenceuuid).list();
    }

    @Transactional
    public void addParticipant(ConferenceParticipant conferenceParticipant) {
        conferenceParticipant.persist();
    }
}
