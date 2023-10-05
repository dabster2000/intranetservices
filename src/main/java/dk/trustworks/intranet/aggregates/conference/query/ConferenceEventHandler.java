package dk.trustworks.intranet.aggregates.conference.query;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceService;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.json.JsonObject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.READ_CONFERENCE_EVENT;
import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.SEND_BROWSER_EVENT;

@JBossLog
@ApplicationScoped
public class ConferenceEventHandler {

    @Inject
    ConferenceService conferenceService;

    @Blocking
    @Incoming(READ_CONFERENCE_EVENT)
    @Outgoing(SEND_BROWSER_EVENT)
    public String readConferenceEvent(AggregateRootChangeEvent event) {
        AggregateEventType type = event.getEventType();
        switch (type) {
            case CREATE_CONFERENCE_PARTICIPANT -> createConferenceParticipant(event);
            case UPDATE_CONFERENCE_PARTICIPANT -> updateConferenceParticipantData(event);
            case CHANGE_CONFERENCE_PARTICIPANT_PHASE -> changeConferenceParticipantPhase(event);
        }
        return event.getAggregateRootUUID();
    }

    private void createConferenceParticipant(AggregateRootChangeEvent event) {
        ConferenceParticipant conferenceParticipant = new JsonObject(event.getEventContent()).mapTo(ConferenceParticipant.class);
        conferenceService.createParticipant(conferenceParticipant);
    }

    private void updateConferenceParticipantData(AggregateRootChangeEvent event) {
        ConferenceParticipant conferenceParticipant = new JsonObject(event.getEventContent()).mapTo(ConferenceParticipant.class);
        conferenceService.updateParticipantData(conferenceParticipant);
    }

    private void changeConferenceParticipantPhase(AggregateRootChangeEvent event) {
        ConferenceParticipant conferenceParticipant = new JsonObject(event.getEventContent()).mapTo(ConferenceParticipant.class);
        conferenceService.changeParticipantPhase(conferenceParticipant);
    }
}
