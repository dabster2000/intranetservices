package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.enums.ConferenceApplicationStatus;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class ConferenceService {

    @Inject
    MailResource mailResource;

    public List<ConferenceParticipant> findByConference(String conferenceuuid) {
        return ConferenceParticipant.find("conferenceuuid like ?1", conferenceuuid).list();
    }

    @Transactional
    public void addParticipant(ConferenceParticipant conferenceParticipant) {
        conferenceParticipant.setConferenceuuid("dda7c9b8-afb3-4ff6-99bd-a5d7788354b8");
        conferenceParticipant.persist();
        mailResource.sendingWaitingListMail(conferenceParticipant.getEmail());
    }

    @Transactional
    public void inviteParticipants(List<ConferenceParticipant> participants) {
        for (ConferenceParticipant participant : participants) {
            if(participant.getStatus()==ConferenceApplicationStatus.APPROVED) continue;
            mailResource.sendingInvitationMail(participant.getEmail());
            participant.setUuid(UUID.randomUUID().toString());
            participant.setStatus(ConferenceApplicationStatus.APPROVED);
            participant.setRegistered(LocalDateTime.now());
            participant.persist();
        }
    }
    @Transactional
    public void denyParticipants(List<ConferenceParticipant> participants) {
        for (ConferenceParticipant participant : participants) {
            if(participant.getStatus()==ConferenceApplicationStatus.DENIED) continue;
            mailResource.sendingDenyMail(participant.getEmail());
            participant.setUuid(UUID.randomUUID().toString());
            participant.setStatus(ConferenceApplicationStatus.DENIED);
            participant.setRegistered(LocalDateTime.now());
            participant.persist();
        }
    }

    @Transactional
    public void withdraw(List<ConferenceParticipant> participants) {
        for (ConferenceParticipant participant : participants) {
            if(participant.getStatus()==ConferenceApplicationStatus.WITHDRAWN) continue;
            mailResource.sendingWithdrawMail(participant.getEmail());
            participant.setUuid(UUID.randomUUID().toString());
            participant.setStatus(ConferenceApplicationStatus.WITHDRAWN);
            participant.setRegistered(LocalDateTime.now());
            participant.persist();
        }
    }
}
