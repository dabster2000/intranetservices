package dk.trustworks.intranet.aggregates.conference.query;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceService;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;
import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.CONFERENCE_EVENT;

@JBossLog
@ApplicationScoped
public class ConferenceEventHandler {

    @Inject
    ConferenceService conferenceService;

    @Inject
    EventBus eventBus;

    @ConsumeEvent(value = CONFERENCE_EVENT, blocking = true)
    public void readConferenceEvent(AggregateRootChangeEvent event) {
        AggregateEventType type = event.getEventType();
        switch (type) {
            case CREATE_CONFERENCE_PARTICIPANT -> createConferenceParticipant(event);
            case UPDATE_CONFERENCE_PARTICIPANT -> updateConferenceParticipantData(event);
            case CHANGE_CONFERENCE_PARTICIPANT_PHASE -> changeConferenceParticipantPhase(event);
        }
        eventBus.publish(BROWSER_EVENT, event.getAggregateRootUUID());
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
