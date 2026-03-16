package dk.trustworks.intranet.aggregates.work.query;

import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class WorkEventHandler {

    @ConsumeEvent(value = "domain.events.UPDATE_WORK", blocking = true)
    public void onUpdateWork(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        log.infof("WorkEventHandler.onUpdateWork: eventId=%s, aggregateId=%s, eventType=%s, actor=%s, occurredAt=%s",
                env.getEventId(), env.getAggregateId(), env.getEventType(), env.getActor(), env.getOccurredAt());
        updateWork(env);
    }

    private void updateWork(DomainEventEnvelope env) {
        log.debugf("updateWork processing: eventId=%s, aggregateId=%s, payload=%s",
                env.getEventId(), env.getAggregateId(), env.getPayload());
        // TODO: Implement work aggregate projection update
    }
}
