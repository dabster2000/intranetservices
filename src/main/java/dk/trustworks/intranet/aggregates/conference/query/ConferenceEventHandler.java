package dk.trustworks.intranet.aggregates.conference.query;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceService;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
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

    @ConsumeEvent(value = "domain.events.CREATE_CONFERENCE_PARTICIPANT", blocking = true)
    public void onCreateConferenceParticipant(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        createConferenceParticipant(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.UPDATE_CONFERENCE_PARTICIPANT", blocking = true)
    public void onUpdateConferenceParticipant(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        updateConferenceParticipantData(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.CHANGE_CONFERENCE_PARTICIPANT_PHASE", blocking = true)
    public void onChangeConferenceParticipantPhase(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        changeConferenceParticipantPhase(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    private void createConferenceParticipant(DomainEventEnvelope env) {
        ConferenceParticipant conferenceParticipant = new JsonObject(env.getPayload()).mapTo(ConferenceParticipant.class);
        conferenceService.createParticipant(conferenceParticipant);
    }

    private void updateConferenceParticipantData(DomainEventEnvelope env) {
        ConferenceParticipant conferenceParticipant = new JsonObject(env.getPayload()).mapTo(ConferenceParticipant.class);
        conferenceService.updateParticipantData(conferenceParticipant);
    }

    private void changeConferenceParticipantPhase(DomainEventEnvelope env) {
        ConferenceParticipant conferenceParticipant = new JsonObject(env.getPayload()).mapTo(ConferenceParticipant.class);
        conferenceService.changeParticipantPhase(conferenceParticipant);
    }
}
