package dk.trustworks.intranet.aggregates.conference.query;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceService;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Consumer;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;

/**
 * Applies committed conference domain events to the participant projection.
 * <p>
 * Every method here runs on a worker thread, long after the REST caller was given its
 * response: {@code AggregateMessageEmitter} uses {@code eventBus.publish(...)}, which has
 * no reply address, and Quarkus only rethrows a consumer exception onto Vert.x's default
 * uncaught-exception logger. Nothing propagates back to the producer. A failure here is
 * therefore never visible to the user who submitted the form — on 2026-08-03 an
 * over-length message rolled the insert back and the registrant was still shown success.
 * <p>
 * So each delivery is isolated and logged loudly rather than left to escape as an
 * {@code ArcUndeclaredThrowableException} with no context (the same shape
 * {@code RecruitmentEventDispatcher} already uses). The event itself is durable in
 * {@code aggregate_events} before the publish, so the error line carries the coordinates
 * needed to find that row and replay it. The payload is deliberately not logged: it holds
 * submitter name, email and free text.
 */
@JBossLog
@ApplicationScoped
public class ConferenceEventHandler {

    @Inject
    ConferenceService conferenceService;

    @Inject
    EventBus eventBus;

    @ConsumeEvent(value = "domain.events.CREATE_CONFERENCE_PARTICIPANT", blocking = true)
    public void onCreateConferenceParticipant(String envelopeJson) {
        apply(envelopeJson, this::createConferenceParticipant);
    }

    @ConsumeEvent(value = "domain.events.UPDATE_CONFERENCE_PARTICIPANT", blocking = true)
    public void onUpdateConferenceParticipant(String envelopeJson) {
        apply(envelopeJson, this::updateConferenceParticipantData);
    }

    @ConsumeEvent(value = "domain.events.CHANGE_CONFERENCE_PARTICIPANT_PHASE", blocking = true)
    public void onChangeConferenceParticipantPhase(String envelopeJson) {
        apply(envelopeJson, this::changeConferenceParticipantPhase);
    }

    @ConsumeEvent(value = "domain.events.DELETE_CONFERENCE_PARTICIPANT", blocking = true)
    public void onDeleteConferenceParticipant(String envelopeJson) {
        apply(envelopeJson, this::deleteConferenceParticipant);
    }

    /**
     * Runs one projection step, then notifies browsers only if it actually committed.
     * <p>
     * The exception is swallowed on purpose: rethrowing would only reach Vert.x's default
     * logger, which is what produced the uninformative stack trace in production. The
     * error logged here is the alertable signal, and it names the {@code aggregate_events}
     * row the submission can be recovered from.
     */
    void apply(String envelopeJson, Consumer<DomainEventEnvelope> projection) {
        DomainEventEnvelope env;
        try {
            env = DomainEventEnvelope.fromJson(envelopeJson);
        } catch (Exception e) {
            log.error("CONFERENCE PROJECTION FAILED: unreadable event envelope — submission lost, nothing to replay", e);
            return;
        }
        if (env == null) {
            // Jackson maps the literal "null" to null rather than throwing. Guard it here:
            // dereferencing env inside the catch below would throw from within the handler
            // that exists to contain throwables, and escape to Vert.x after all.
            log.error("CONFERENCE PROJECTION FAILED: null event envelope — submission lost, nothing to replay");
            return;
        }
        try {
            projection.accept(env);
        } catch (Exception e) {
            log.errorf(e, "CONFERENCE PROJECTION FAILED: %s for conference %s was acknowledged to the caller but NOT written. "
                            + "Recover with: SELECT event_content FROM aggregate_events WHERE event_type='%s' "
                            + "AND aggregate_root_uuid='%s' AND event_time>='%s';",
                    env.getEventType(), env.getAggregateId(),
                    env.getEventType(), env.getAggregateId(), env.getOccurredAt());
            return;
        }
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

    private void deleteConferenceParticipant(DomainEventEnvelope env) {
        ConferenceParticipant conferenceParticipant = new JsonObject(env.getPayload()).mapTo(ConferenceParticipant.class);
        conferenceService.deleteParticipant(conferenceParticipant.getConferenceuuid(), conferenceParticipant.getParticipantuuid());
    }
}
